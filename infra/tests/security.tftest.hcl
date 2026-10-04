mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_db_instance" {
    defaults = { address = "db.example.invalid", master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-south-1:123456789012:secret:fixture", kms_key_id = "fixture", secret_status = "active" }] }
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
}
run "thin_delivery_security_contract" {
  command = plan
  assert {
    condition     = !one(aws_budgets_budget.account_guard.cost_types).include_credit && !one(aws_budgets_budget.account_guard.cost_types).include_refund
    error_message = "Credits and refunds must not offset spend counted by the account budget alert."
  }
  assert {
    condition = (
      aws_budgets_budget.account_guard.budget_type == "COST" &&
      aws_budgets_budget.account_guard.time_unit == "MONTHLY" &&
      aws_budgets_budget.account_guard.limit_unit == "USD" &&
      tonumber(aws_budgets_budget.account_guard.limit_amount) == 10 &&
      toset([for notification in aws_budgets_budget.account_guard.notification : "${notification.notification_type}:${notification.threshold}"]) == toset(["ACTUAL:50", "FORECASTED:80", "ACTUAL:100"]) &&
      alltrue([for notification in aws_budgets_budget.account_guard.notification :
        notification.threshold_type == "PERCENTAGE" && notification.comparison_operator == "GREATER_THAN" && notification.subscriber_email_addresses == toset(["fixture@example.invalid"])
      ])
    )
    error_message = "Excluding credits/refunds must preserve the approved monthly USD limit and existing actual/forecasted email alerts."
  }
  assert {
    condition     = !aws_db_instance.this.publicly_accessible && aws_db_instance.this.storage_encrypted && aws_db_instance.this.deletion_protection
    error_message = "The database must remain private, encrypted and protected from accidental deletion."
  }
  assert {
    condition     = aws_db_instance.this.manage_master_user_password && aws_db_instance.this.password == null
    error_message = "Terraform must not manage a plaintext master password."
  }
  assert {
    condition     = aws_instance.app.metadata_options[0].http_tokens == "required" && aws_instance.app.metadata_options[0].http_put_response_hop_limit == 1
    error_message = "Application containers must not inherit host instance credentials."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.database.from_port == 5432 && aws_vpc_security_group_ingress_rule.database.cidr_ipv4 == null
    error_message = "RDS access must be scoped to the app security group, never an internet CIDR."
  }
  assert {
    condition     = aws_ecr_repository.app.image_tag_mutability == "IMMUTABLE" && !aws_ecr_repository.app.force_delete
    error_message = "A rollback artifact must not be silently replaced or purged."
  }
  assert {
    condition     = length(aws_iam_role.bootstrap) == 0
    error_message = "Managed project users must not be assigned a new human role by default."
  }
  assert {
    condition     = aws_instance.app.tags.Project == "auctionhouse" && aws_instance.app.tags.SessionId == "fixture" && aws_instance.app.tags.ExpiresAt == "2026-10-02T00:00:00Z"
    error_message = "Every owned resource needs project/session/expiry metadata."
  }
  assert {
    condition     = jsondecode(aws_ssm_document.release.content).parameters.Digest.allowedPattern == "^sha256:[a-f0-9]{64}$"
    error_message = "SSM must accept an immutable digest, not an arbitrary command or mutable tag."
  }
  assert {
    condition     = anytrue([for condition in data.aws_iam_policy_document.github_trust.statement[0].condition : condition.test == "StringEquals" && condition.variable == "token.actions.githubusercontent.com:sub" && length(condition.values) == 1 && contains(condition.values, "repo:fixture/auctionhouse:ref:refs/heads/main")])
    error_message = "GitHub federation must restrict one exact repository and branch, not a wildcard."
  }
}
run "reject_unapproved_spend" {
  command = plan
  variables { approved_monthly_budget_usd = 0 }
  expect_failures = [var.approved_monthly_budget_usd]
}
run "reject_cross_region_subnets" {
  command = plan
  variables { availability_zones = ["us-east-1a", "us-east-1b"] }
  expect_failures = [var.availability_zones]
}
run "reject_wildcard_github_subject" {
  command = plan
  variables { github_repository = "fixture/*" }
  expect_failures = [var.github_repository]
}
