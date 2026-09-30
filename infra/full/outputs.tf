output "ownership" { value = { project = "auctionhouse", session_id = var.session_id, account_id = var.account_id, region = var.region, expires_at = var.expires_at, topology = "full" } }
output "comparison_manifest" {
  value = {
    schemaVersion              = 1
    ownership                  = { project = "auctionhouse", sessionId = var.session_id, accountId = var.account_id, region = var.region, expiresAt = var.expires_at }
    origin                     = "https://${var.domain_name}"
    loadBalancer               = { type = "aws-alb", arn = aws_lb.this.arn, dnsName = aws_lb.this.dns_name, targetGroupArn = aws_lb_target_group.service["app"].arn, gatewayTargetGroupArn = aws_lb_target_group.service["gateway"].arn, certificateArn = var.certificate_arn, appTargetPort = 8080, sticky = false }
    applicationHostCount       = var.application_host_count
    applicationInstanceIds     = aws_instance.app[*].id
    applicationInstanceType    = var.instance_type
    dependencyInstanceId       = aws_instance.dependency.id
    dependencyInstanceType     = var.dependency_instance_type
    supportingEc2HostCount     = 1
    totalEc2HostCount          = var.application_host_count + 1
    database                   = { arn = aws_db_instance.this.arn, host = aws_db_instance.this.address, instanceClass = var.db_instance_class }
    releaseDocumentName        = aws_ssm_document.release.name
    inspectionDocumentName     = aws_ssm_document.inspect.name
    ecrRepositoryUrls          = { for key, repo in aws_ecr_repository.service : key => repo.repository_url }
    runtimeSettings            = local.runtime_settings
    runtimeConfigurationSha256 = local.runtime_configuration_sha256
    stateIsolation             = { module = "infra/full", backendKey = var.state_backend_key }
  }
}
output "deploy_role_arn" { value = aws_iam_role.deploy.arn }
output "bootstrap_policy_contract" { value = data.aws_iam_policy_document.bootstrap.json }
output "database_master_secret_arn" { value = aws_db_instance.this.master_user_secret[0].secret_arn }
output "resource_inventory" {
  value = {
    instances                  = concat(aws_instance.app[*].id, [aws_instance.dependency.id])
    persistentDependencyVolume = aws_ebs_volume.dependencies.id
    loadBalancer               = aws_lb.this.arn
    targetGroups               = [for target in aws_lb_target_group.service : target.arn]
    database                   = aws_db_instance.this.arn
    masterSecret               = aws_db_instance.this.master_user_secret[0].secret_arn
    repositories               = [for repo in aws_ecr_repository.service : repo.arn]
    logGroups                  = concat([for group in aws_cloudwatch_log_group.service : group.arn], [aws_cloudwatch_log_group.database.arn])
    roles                      = concat([for role in aws_iam_role.host : role.arn], [aws_iam_role.deploy.arn])
    ssmDocuments               = [aws_ssm_document.release.arn, aws_ssm_document.inspect.arn]
    ssmParameters              = [for name in ["runtime", "migration", "warehouse", "grafana"] : "${local.parameter_prefix}/${name}"]
    finalDatabaseSnapshot      = "${local.name}-final"
    budget                     = aws_budgets_budget.account_guard.name
    vpc                        = aws_vpc.this.id
    externalNotOwned           = { acmCertificate = var.certificate_arn, githubOidcProvider = var.github_oidc_provider_arn, backendKey = var.state_backend_key }
  }
}
