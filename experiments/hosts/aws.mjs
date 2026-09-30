import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { canonical, requireThat, validateInspection, validateTags } from './contracts.mjs';
const execute = promisify(execFile);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

export class Aws {
  constructor(profile, region, executable = 'aws', executeCommand = execute) {
    requireThat(/^[A-Za-z0-9_-]{1,64}$/.test(profile), 'Use an explicit named AWS profile');
    this.profile = profile; this.region = region; this.executable = executable; this.execute = executeCommand;
  }
  async call(service, operation, ...args) {
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('AWS_ENDPOINT_URL')));
    try {
      const result = await this.execute(this.executable, [service, operation, ...args,
        '--profile', this.profile, '--region', this.region, '--output', 'json', '--no-cli-pager',
        '--cli-connect-timeout', '10', '--cli-read-timeout', '20'],
      { encoding: 'utf8', timeout: 30000, maxBuffer: 16 * 1024 * 1024, windowsHide: true,
        env: { ...env, AWS_PAGER: '', AWS_CLI_AUTO_PROMPT: 'off' } });
      return JSON.parse(result.stdout);
    } catch {
      // CLI errors may contain account configuration or request bodies; keep diagnostics bounded.
      throw new Error(`AWS ${service} ${operation} failed; inspect the named profile independently`);
    }
  }
  async inspect(m, release, dependencyAddress) {
    requireThat(/^10\.74\.\d+\.\d+$/.test(dependencyAddress), 'Observed dependency private address is required');
    const ids = [...m.applicationInstanceIds, m.dependencyInstanceId];
    const inspected = await this.call('ssm', 'get-document', '--name', m.inspectionDocumentName, '--document-format', 'JSON');
    const document = JSON.parse(inspected.Content);
    requireThat(/^[1-9][0-9]*$/.test(inspected.DocumentVersion) && document.schemaVersion === '2.2'
      && Object.keys(document).every(k => ['schemaVersion', 'description', 'mainSteps'].includes(k))
      && canonical(document.mainSteps) === canonical([{ action: 'aws:runShellScript', name: 'inspect',
        inputs: { timeoutSeconds: '60', runCommand: ['python3 /opt/auctionhouse/full/inspect-runtime.py'] } }]),
    'Inspection document differs from the reviewed read-only contract');
    const sent = await this.call('ssm', 'send-command', '--document-name', m.inspectionDocumentName,
      '--document-version', inspected.DocumentVersion,
      '--instance-ids', ...ids, '--timeout-seconds', '60', '--comment', 'Read-only Auctionhouse experiment inspection');
    const commandId = sent.Command?.CommandId;
    requireThat(typeof commandId === 'string', 'No inspection command identity');
    const results = [];
    for (const id of ids) {
      let invocation;
      for (let attempt = 0; attempt < 30; attempt++) {
        try { invocation = await this.call('ssm', 'get-command-invocation', '--command-id', commandId, '--instance-id', id); }
        catch { if (attempt === 29) throw new Error('Inspection invocation unavailable'); }
        if (['Success', 'Failed', 'TimedOut', 'Cancelled'].includes(invocation?.Status)) break;
        await delay(1000);
      }
      requireThat(invocation?.Status === 'Success' && invocation.ResponseCode === 0, 'Scoped inspection failed');
      const report = JSON.parse(invocation.StandardOutputContent);
      validateInspection(report, m, release, id === m.dependencyInstanceId ? 'dependency' : 'app', dependencyAddress);
      results.push({ instanceId: id, report });
    }
    return { commandId, recordedAt: new Date().toISOString(), results };
  }
  async preflight(m, release) {
    requireThat(this.region === m.ownership.region, 'AWS CLI region and manifest differ');
    const caller = await this.call('sts', 'get-caller-identity');
    requireThat(caller.Account === m.ownership.accountId, 'Authenticated AWS account differs from manifest');
    const allIds = [...m.applicationInstanceIds, m.dependencyInstanceId];
    const described = await this.call('ec2', 'describe-instances', '--instance-ids', ...allIds);
    const instances = described.Reservations.flatMap(r => r.Instances);
    requireThat(canonical(instances.map(i => i.InstanceId).sort()) === canonical([...allIds].sort()), 'Instance inventory mismatch');
    for (const i of instances) {
      validateTags(i.Tags, m.ownership);
      requireThat(i.State.Name === 'running' && i.InstanceType === (i.InstanceId === m.dependencyInstanceId
        ? m.dependencyInstanceType : m.applicationInstanceType), 'Instance state/type mismatch');
    }
    const lb = (await this.call('elbv2', 'describe-load-balancers', '--load-balancer-arns', m.loadBalancer.arn)).LoadBalancers;
    requireThat(lb.length === 1 && lb[0].Type === 'application' && lb[0].State.Code === 'active'
      && lb[0].Scheme === 'internet-facing' && lb[0].DNSName === m.loadBalancer.dnsName, 'ALB inventory mismatch');
    const tagDescriptions = (await this.call('elbv2', 'describe-tags', '--resource-arns',
      m.loadBalancer.arn, m.loadBalancer.targetGroupArn)).TagDescriptions;
    requireThat(tagDescriptions.length === 2, 'ALB/target group tags absent');
    tagDescriptions.forEach(t => validateTags(t.Tags, m.ownership));
    const group = (await this.call('elbv2', 'describe-target-groups', '--target-group-arns', m.loadBalancer.targetGroupArn)).TargetGroups[0];
    requireThat(group.TargetType === 'instance' && group.Protocol === 'HTTP' && group.Port === 8080
      && group.LoadBalancerArns.includes(m.loadBalancer.arn), 'Wrong ALB target group');
    const attributes = (await this.call('elbv2', 'describe-target-group-attributes', '--target-group-arn', m.loadBalancer.targetGroupArn)).Attributes;
    requireThat(attributes.find(a => a.Key === 'stickiness.enabled')?.Value === 'false', 'Sticky sessions invalidate comparison');
    const health = (await this.call('elbv2', 'describe-target-health', '--target-group-arn', m.loadBalancer.targetGroupArn)).TargetHealthDescriptions;
    requireThat(canonical(health.map(h => h.Target.Id).sort()) === canonical([...m.applicationInstanceIds].sort())
      && health.every(h => h.TargetHealth.State === 'healthy' && h.Target.Port === 8080), 'Exact application target set must be healthy');
    const listeners = (await this.call('elbv2', 'describe-listeners', '--load-balancer-arn', m.loadBalancer.arn)).Listeners;
    const https = listeners.find(l => l.Port === 443 && l.Protocol === 'HTTPS');
    requireThat(https?.Certificates?.some(c => c.CertificateArn === m.loadBalancer.certificateArn)
      && https.DefaultActions.some(a => a.Type === 'fixed-response' && a.FixedResponseConfig?.StatusCode === '403'),
    'HTTPS listener/certificate/default denial mismatch');
    const rules = (await this.call('elbv2', 'describe-rules', '--listener-arn', https.ListenerArn)).Rules;
    const hostname = new URL(m.origin).hostname;
    requireThat(rules.some(r => r.Conditions.some(c => c.Field === 'host-header'
      && canonical(c.HostHeaderConfig?.Values || c.Values) === canonical([hostname]))
      && !r.Conditions.some(c => c.Field === 'path-pattern')
      && r.Actions.length === 1 && r.Actions.every(a => a.Type === 'forward'
        && (!a.TargetGroupArn || a.TargetGroupArn === m.loadBalancer.targetGroupArn)
        && (a.ForwardConfig ? a.ForwardConfig.TargetGroups?.length === 1
          && a.ForwardConfig.TargetGroups[0].TargetGroupArn === m.loadBalancer.targetGroupArn
          && (a.ForwardConfig.TargetGroups[0].Weight ?? 1) > 0 : a.TargetGroupArn === m.loadBalancer.targetGroupArn))),
    'No exact-host application forwarding rule');
    const databaseId = m.database.arn.split(':db:')[1];
    const db = (await this.call('rds', 'describe-db-instances', '--db-instance-identifier', databaseId)).DBInstances[0];
    validateTags((await this.call('rds', 'list-tags-for-resource', '--resource-name', m.database.arn)).TagList, m.ownership);
    requireThat(db.DBInstanceArn === m.database.arn && db.DBInstanceStatus === 'available' && !db.PubliclyAccessible
      && db.StorageEncrypted && db.Engine === 'postgres' && db.DBInstanceClass === m.database.instanceClass
      && db.Endpoint.Address === m.database.host, 'Private database shape mismatch');
    const inspection = await this.inspect(m, release, instances.find(i => i.InstanceId === m.dependencyInstanceId).PrivateIpAddress);
    return { recordedAt: new Date().toISOString(), accountId: caller.Account,
      instances: instances.map(i => ({ instanceId: i.InstanceId, type: i.InstanceType, imageId: i.ImageId, privateIpAddress: i.PrivateIpAddress,
        zone: i.Placement.AvailabilityZone, state: i.State.Name })),
      loadBalancer: { arn: lb[0].LoadBalancerArn, dnsName: lb[0].DNSName, targetHealth: health, attributes },
      database: { arn: db.DBInstanceArn, instanceClass: db.DBInstanceClass, engineVersion: db.EngineVersion,
        allocatedStorage: db.AllocatedStorage, storageType: db.StorageType, multiAZ: db.MultiAZ }, inspection };
  }
  async metrics(m, startedAt, finishedAt) {
    const records = [];
    const requests = [
      ...[...m.applicationInstanceIds, m.dependencyInstanceId].flatMap(id => ['CPUUtilization', 'CPUCreditBalance', 'NetworkIn', 'NetworkOut'].map(metric =>
        ({ namespace: 'AWS/EC2', metric, dimension: 'InstanceId', value: id }))),
      ...['CPUUtilization', 'DatabaseConnections', 'FreeableMemory', 'ReadLatency', 'WriteLatency'].map(metric =>
        ({ namespace: 'AWS/RDS', metric, dimension: 'DBInstanceIdentifier', value: m.database.arn.split(':db:')[1] })),
      ...['TargetResponseTime', 'RequestCount', 'HTTPCode_Target_5XX_Count'].map(metric =>
        ({ namespace: 'AWS/ApplicationELB', metric, dimension: 'LoadBalancer', value: m.loadBalancer.arn.split(':loadbalancer/')[1] })),
    ];
    for (const q of requests) {
      try {
        const result = await this.call('cloudwatch', 'get-metric-statistics', '--namespace', q.namespace,
          '--metric-name', q.metric, '--dimensions', `Name=${q.dimension},Value=${q.value}`,
          '--start-time', startedAt, '--end-time', finishedAt, '--period', '60', '--statistics', 'Average', 'Maximum', 'Sum');
        records.push({ ...q, status: result.Datapoints.length ? 'observed' : 'unavailable-or-not-yet-published',
          datapoints: result.Datapoints.sort((a, b) => a.Timestamp.localeCompare(b.Timestamp)) });
      } catch { records.push({ ...q, status: 'query-failed', datapoints: [] }); }
    }
    return { recordedAt: new Date().toISOString(), start: startedAt, end: finishedAt,
      scope: 'CloudWatch one-minute buckets overlap short runs; metrics may lag or use five-minute EC2 basic monitoring. Empty series are unknown, never zero or capacity evidence.', records };
  }
}
