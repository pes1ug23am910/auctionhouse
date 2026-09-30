variable "account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Use the approved 12-digit AWS account."
  }
}
variable "session_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,20}$", var.session_id))
    error_message = "Use a short project session slug."
  }
}
variable "owner" {
  type = string
}
variable "expires_at" {
  type = string
  validation {
    condition     = can(timecmp(var.expires_at, "2000-01-01T00:00:00Z"))
    error_message = "Expiry must be an explicit RFC3339 timestamp."
  }
}
variable "approved_monthly_budget_usd" {
  type = number
  validation {
    condition     = var.approved_monthly_budget_usd > 0 && var.approved_monthly_budget_usd <= 100
    error_message = "Explicitly approve a monthly budget in (0, 100] USD; this alert is not a hard cap."
  }
}
variable "budget_email" {
  type = string
  validation {
    condition     = can(regex("^[^@]+@[^@]+\\.[^@]+$", var.budget_email))
    error_message = "A budget alert recipient is required."
  }
}
variable "domain_name" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.domain_name))
    error_message = "Supply an owned DNS hostname for TLS; no wildcard or localhost."
  }
}
variable "ami_id" {
  description = "Reviewed Amazon Linux 2023 x86_64 AMI in the selected Region, pinned per release. No moving latest AMI lookup."
  type        = string
  validation {
    condition     = can(regex("^ami-[a-f0-9]{17}$", var.ami_id))
    error_message = "Pin a reviewed selected-Region x86_64 AMI."
  }
}
variable "github_repository" {
  type = string
  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "Use exact owner/repository, without wildcards."
  }
}
variable "github_ref" {
  type    = string
  default = "refs/heads/main"
  validation {
    condition     = can(regex("^refs/heads/[A-Za-z0-9._/-]+$", var.github_ref)) && !strcontains(var.github_ref, "*")
    error_message = "Use one exact protected branch ref."
  }
}
variable "github_oidc_provider_arn" {
  description = "Project-bootstrap-owned GitHub OIDC provider. This module does not create or destroy it."
  type        = string
  validation {
    condition     = var.github_oidc_provider_arn == "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"
    error_message = "Use only the GitHub OIDC provider in the explicitly selected project."
  }
}
variable "bootstrap_principal_arn" {
  description = "An approved human IAM role/user for the separate MFA-only database bootstrap role."
  type        = string
  default     = ""
}
variable "instance_type" {
  type    = string
  default = "t3.small"
  validation {
    condition     = contains(["t3.small", "t3.medium"], var.instance_type)
    error_message = "Choose a bounded, measured x86 size; do not enable a full broker stack on the thin host."
  }
}
variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
  validation {
    condition     = contains(["db.t4g.micro", "db.t4g.small"], var.db_instance_class)
    error_message = "Choose a bounded RDS class after confirming availability."
  }
}
variable "db_engine_version" {
  type    = string
  default = "18.6"
}
variable "allow_destroy" {
  description = "Explicit maintenance switch required before removing RDS deletion protection."
  type        = bool
  default     = false
}

variable "region" {
  description = "The selected Region confirmed in AWS Settings; no cross-Region resources."
  type        = string
  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]+$", var.region))
    error_message = "Use the project's confirmed selected Region."
  }
}
variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(var.availability_zones) == 2 && alltrue([for az in var.availability_zones : startswith(az, var.region)])
    error_message = "Use two available zones in the confirmed selected Region."
  }
}
variable "create_bootstrap_role" {
  description = "Optional conventional IAM deployment only; managed AWS project users normally use their existing authorized access."
  type        = bool
  default     = false
}
