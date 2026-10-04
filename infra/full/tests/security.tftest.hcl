mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_db_instance" {
    defaults = { address = "db.example.invalid", master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:fixture", kms_key_id = "fixture", secret_status = "active" }] }
  }
  mock_resource "aws_instance" {
    defaults = { private_ip = "10.74.1.42" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/fixture/0123456789abcdef" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/fixture/0123456789abcdef" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:listener/app/fixture/0123456789abcdef/0123456789abcdef" }
  }
}
variables {
  account_id                  = "123456789012"
  region                      = "ap-south-1"
  availability_zones          = ["ap-south-1a", "ap-south-1b"]
  session_id                  = "fixture"
  owner                       = "local-test"
  expires_at                  = "2026-10-02T00:00:00Z"
  approved_monthly_budget_usd = 10
  budget_email                = "fixture@example.invalid"
  domain_name                 = "auction.example.invalid"
  ami_id                      = "ami-0123456789abcdef0"
  github_repository           = "fixture/auctionhouse"
  github_oidc_provider_arn    = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  certificate_arn             = "arn:aws:acm:ap-south-1:123456789012:certificate/01234567-89ab-cdef-0123-456789abcdef"
  state_backend_key           = "auctionhouse/fixture/full/terraform.tfstate"
}
run "one_application_plus_one_supporting_host" {
  command = apply
  assert {
    condition     = aws_instance.dependency.instance_type == "t3.large" && one(aws_instance.dependency.credit_specification).cpu_credits == "standard" && alltrue([for host in aws_instance.app : one(host.credit_specification).cpu_credits == "standard"])
    error_message = "The existing T3 default and standard CPU credit mode must be preserved."
  }
  assert {
    condition     = length(aws_instance.app) == 1 && output.comparison_manifest.totalEc2HostCount == 2 && output.comparison_manifest.supportingEc2HostCount == 1
    error_message = "One application host must report its additional dependency host, not imply one total host."
  }
  assert {
    condition     = !aws_db_instance.this.publicly_accessible && aws_db_instance.this.storage_encrypted && aws_db_instance.this.deletion_protection && aws_db_instance.this.manage_master_user_password && aws_db_instance.this.password == null
    error_message = "The shared DB must stay private/encrypted/protected with AWS-managed master credentials."
  }
  assert {
    condition     = aws_lb.this.enable_deletion_protection && aws_lb.this.idle_timeout == 120 && !aws_lb_target_group.service["app"].stickiness[0].enabled
    error_message = "ALB lifecycle, SSE idle timeout and shared-session no-stickiness contract changed."
  }
  assert {
    condition     = aws_lb_listener.https.default_action[0].type == "fixed-response" && length(one(one(aws_lb_listener_rule.app.condition).host_header).values) == 1 && contains(one(one(aws_lb_listener_rule.app.condition).host_header).values, "auction.example.invalid")
    error_message = "Unknown HTTP Host values must not reach the application."
  }
  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_ingress_rule.target : rule.cidr_ipv4 == null && rule.referenced_security_group_id == aws_security_group.service["alb"].id]) && alltrue([for rule in aws_vpc_security_group_ingress_rule.dependency : rule.cidr_ipv4 == null && rule.referenced_security_group_id == aws_security_group.service["app"].id])
    error_message = "Application/dependency ingress must be security-group scoped, never public."
  }
  assert {
    condition     = alltrue([for host in concat(aws_instance.app, [aws_instance.dependency]) : host.metadata_options[0].http_tokens == "required" && host.metadata_options[0].http_put_response_hop_limit == 1])
    error_message = "Every host must retain the IMDSv2/hop-limit container boundary."
  }
  assert {
    condition     = alltrue([for host in concat(aws_instance.app, [aws_instance.dependency]) : length(nonsensitive(host.user_data_base64)) <= 21848])
    error_message = "Compressed user data exceeds EC2's 16KiB raw limit."
  }
  assert {
    condition     = local.parameter_prefix == "/auctionhouse/fixture/full" && output.comparison_manifest.stateIsolation.backendKey == "auctionhouse/fixture/full/terraform.tfstate"
    error_message = "Full deployment secrets and state must not collide with the thin root."
  }
  assert {
    condition     = jsondecode(aws_ssm_document.inspect.content).mainSteps[0].inputs.runCommand == ["python3 /opt/auctionhouse/full/inspect-runtime.py"] && !can(jsondecode(aws_ssm_document.inspect.content).parameters)
    error_message = "Inspection must remain a fixed read-only command, not an arbitrary shell interface."
  }
  assert {
    condition     = alltrue([for kind in ["AppDigest", "GatewayDigest", "WarehouseDigest"] : jsondecode(aws_ssm_document.release.content).parameters[kind].allowedPattern == "^sha256:[a-f0-9]{64}$"])
    error_message = "Only immutable image digests may reach the release command."
  }
}
run "two_application_hosts_share_dependencies" {
  command = apply
  variables { application_host_count = 2 }
  assert {
    condition     = length(aws_instance.app) == 2 && length(aws_lb_target_group_attachment.app) == 2 && output.comparison_manifest.totalEc2HostCount == 3
    error_message = "Two application hosts must register exactly two app targets and report three total EC2 hosts."
  }
  assert {
    condition     = output.comparison_manifest.dependencyInstanceId == run.one_application_plus_one_supporting_host.comparison_manifest.dependencyInstanceId && output.comparison_manifest.database.arn == run.one_application_plus_one_supporting_host.comparison_manifest.database.arn && output.comparison_manifest.runtimeConfigurationSha256 == run.one_application_plus_one_supporting_host.comparison_manifest.runtimeConfigurationSha256
    error_message = "Host-count comparison must preserve the same shared dependency/DB and per-host settings."
  }
}
run "free_plan_supporting_host_preserves_full_topology" {
  command = apply
  variables {
    application_host_count   = 2
    dependency_instance_type = "m7i-flex.large"
  }
  assert {
    condition     = aws_instance.dependency.instance_type == "m7i-flex.large" && length(aws_instance.dependency.credit_specification) == 0 && alltrue([for host in aws_instance.app : one(host.credit_specification).cpu_credits == "standard"])
    error_message = "M7i-flex must omit T-family CPU credits without changing app-host credit settings."
  }
  assert {
    condition     = length(aws_instance.app) == 2 && output.comparison_manifest.totalEc2HostCount == 3 && output.comparison_manifest.dependencyInstanceType == "m7i-flex.large" && output.comparison_manifest.runtimeConfigurationSha256 == run.one_application_plus_one_supporting_host.comparison_manifest.runtimeConfigurationSha256
    error_message = "The Free-plan support option must preserve the full topology and per-app runtime settings and report the chosen type."
  }
}
run "reject_undersized_supporting_host" {
  command = plan
  variables { dependency_instance_type = "t3.small" }
  expect_failures = [var.dependency_instance_type]
}
run "reject_unapproved_spend" {
  command = plan
  variables { approved_monthly_budget_usd = 0 }
  expect_failures = [var.approved_monthly_budget_usd]
}
run "reject_unbounded_host_count" {
  command = plan
  variables { application_host_count = 3 }
  expect_failures = [var.application_host_count]
}
run "reject_wrong_certificate_region" {
  command = plan
  variables { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/01234567-89ab-cdef-0123-456789abcdef" }
  expect_failures = [var.certificate_arn]
}
run "reject_thin_backend_key" {
  command = plan
  variables { state_backend_key = "auctionhouse/fixture/terraform.tfstate" }
  expect_failures = [var.state_backend_key]
}
