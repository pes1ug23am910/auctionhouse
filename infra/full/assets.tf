locals {
  assets = merge({ for name in ["bootstrap.sh", "release.sh", "inspect-runtime.py", "dependencies.sh", "warehouse.sh", "runtime-secrets.py", "smoke.sh"] : name => file("${path.module}/../../ops/cloud/full/${name}") }, {
    "observability/collector.yaml"  = file("${path.module}/../../observability/collector.yaml")
    "observability/tempo.yaml"      = file("${path.module}/../../observability/tempo.yaml")
    "observability/prometheus.yaml" = file("${path.module}/../../observability/prometheus.yaml")
    "observability/alerts.yaml"     = file("${path.module}/../../observability/alerts.yaml")
  }, { for name in fileset("${path.module}/../../observability/grafana", "**") : "observability/grafana/${name}" => file("${path.module}/../../observability/grafana/${name}") })
  host_common = {
    region                     = var.region, accountId = var.account_id, sessionId = var.session_id
    origin                     = "https://${var.domain_name}", dbHost = aws_db_instance.this.address
    parameterPrefix            = local.parameter_prefix
    repositories               = { for key, repo in aws_ecr_repository.service : key => repo.repository_url }
    logGroups                  = { for key, group in aws_cloudwatch_log_group.service : key => group.name }
    runtimeSettings            = local.runtime_settings
    runtimeConfigurationSha256 = local.runtime_configuration_sha256
    applicationHostCount       = var.application_host_count
  }
}
