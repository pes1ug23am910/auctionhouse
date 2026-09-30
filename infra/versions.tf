terraform {
  backend "s3" {}
  required_version = "= 1.16.4"
  required_providers {
    aws = { source = "hashicorp/aws", version = "= 6.66.0"
    }

  }
}
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = local.tags
  }
}
