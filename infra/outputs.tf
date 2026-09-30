output "ownership" { value = { project = "auctionhouse", session_id = var.session_id, account_id = var.account_id, region = var.region, expires_at = var.expires_at } }
output "repository_url" {
  value = aws_ecr_repository.app.repository_url
}
output "instance_id" {
  value = aws_instance.app.id
}
output "public_ip" {
  value = aws_eip.app.public_ip
}
output "database_host" {
  value = aws_db_instance.this.address
}
output "database_master_secret_arn" {
  value = aws_db_instance.this.master_user_secret[0].secret_arn
}
output "deploy_role_arn" {
  value = aws_iam_role.deploy.arn
}
output "bootstrap_role_arn" {
  value = try(aws_iam_role.bootstrap[0].arn, null)
}
output "release_document" {
  value = aws_ssm_document.release.name
}
output "parameter_prefix" {
  value = local.parameter_prefix
}
output "url" { value = "https://${var.domain_name}" }
output "resource_inventory" {
  value = {
    vpc            = aws_vpc.this.id
    elastic_ip     = aws_eip.app.id
    instance       = aws_instance.app.id
    database       = aws_db_instance.this.arn
    master_secret  = aws_db_instance.this.master_user_secret[0].secret_arn
    repository     = aws_ecr_repository.app.arn
    log_group      = aws_cloudwatch_log_group.app.arn
    database_logs  = aws_cloudwatch_log_group.database.arn
    deploy_role    = aws_iam_role.deploy.arn
    bootstrap_role = try(aws_iam_role.bootstrap[0].arn, null)
    runtime_role   = aws_iam_role.app.arn
    budget         = aws_budgets_budget.account_guard.name
    ssm_parameters = ["${local.parameter_prefix}/runtime", "${local.parameter_prefix}/migration"]
    final_snapshot = "${local.name}-final"
  }
}

output "bootstrap_policy_contract" { value = data.aws_iam_policy_document.bootstrap.json }
