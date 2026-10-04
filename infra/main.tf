locals {
  name             = "auctionhouse-${var.session_id}"
  parameter_prefix = "/auctionhouse/${var.session_id}"
  tags = { Project = "auctionhouse", SessionId = var.session_id, Owner = var.owner, ExpiresAt = var.expires_at, ManagedBy = "Terraform"
  }
}
resource "aws_vpc" "this" {
  cidr_block           = "10.73.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.tags, { Name = local.name })
}
resource "aws_subnet" "app" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = "10.73.1.0/24"
  availability_zone       = var.availability_zones[0]
  map_public_ip_on_launch = true
  tags = { Name = "${local.name}-app"
  }
}
resource "aws_subnet" "database" {
  for_each                = { for index, zone in var.availability_zones : zone => "10.73.${10 + index}.0/24" }
  vpc_id                  = aws_vpc.this.id
  cidr_block              = each.value
  availability_zone       = each.key
  map_public_ip_on_launch = false
  tags = { Name = "${local.name}-db-${each.key}"
  }
}
resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
}
resource "aws_route_table" "app" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
}
resource "aws_route_table_association" "app" {
  subnet_id      = aws_subnet.app.id
  route_table_id = aws_route_table.app.id
}
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id
}
resource "aws_route_table_association" "database" {
  for_each       = aws_subnet.database
  subnet_id      = each.value.id
  route_table_id = aws_route_table.database.id
}
resource "aws_security_group" "app" {
  name   = "${local.name}-app"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_ingress_rule" "http_challenge" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}
resource "aws_security_group" "database" {
  name   = "${local.name}-db"
  vpc_id = aws_vpc.this.id
}
resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "database" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.database.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = [for subnet in aws_subnet.database : subnet.id]
}
resource "aws_db_parameter_group" "this" {
  name   = local.name
  family = "postgres18"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_statement"
    value = "none"
  }
}
resource "aws_cloudwatch_log_group" "database" {
  name              = "/aws/rds/instance/${local.name}/postgresql"
  retention_in_days = 7
}
resource "aws_db_instance" "this" {
  depends_on                      = [aws_cloudwatch_log_group.database]
  identifier                      = local.name
  engine                          = "postgres"
  engine_version                  = var.db_engine_version
  instance_class                  = var.db_instance_class
  allocated_storage               = 20
  max_allocated_storage           = 20
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "auctionhouse"
  username                        = "ah_bootstrap"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.this.name
  parameter_group_name            = aws_db_parameter_group.this.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  publicly_accessible             = false
  multi_az                        = false
  backup_retention_period         = 1
  backup_window                   = "19:00-19:30"
  maintenance_window              = "sun:20:00-sun:21:00"
  auto_minor_version_upgrade      = false
  allow_major_version_upgrade     = false
  deletion_protection             = !var.allow_destroy
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-final"
  copy_tags_to_snapshot           = true
  delete_automated_backups        = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
}
resource "aws_ecr_repository" "app" {
  name                 = local.name
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}
resource "aws_cloudwatch_log_group" "app" {
  name              = "/auctionhouse/${var.session_id}/app"
  retention_in_days = 7
}
resource "aws_instance" "app" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.app.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }
  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }
  credit_specification {
    cpu_credits = "standard"
  }
  user_data_replace_on_change = true
  user_data = templatefile("${path.module}/host-init.sh.tftpl", {
    domain         = var.domain_name
    db_host        = aws_db_instance.this.address
    region         = var.region
    project        = local.name
    prefix         = local.parameter_prefix
    repository     = aws_ecr_repository.app.repository_url
    log_group      = aws_cloudwatch_log_group.app.name
    release_script = file("${path.module}/../ops/cloud/release.sh")
    smoke_script   = file("${path.module}/../ops/cloud/smoke.sh")
    caddyfile      = file("${path.module}/../ops/cloud/Caddyfile")
  })
  tags = merge(local.tags, { Name = local.name })
}
resource "aws_budgets_budget" "account_guard" {
  name         = "${local.name}-account-alert"
  budget_type  = "COST"
  limit_amount = tostring(var.approved_monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
  # Track costs before credits/refunds so promotional credits cannot hide spend.
  cost_types {
    include_credit = false
    include_refund = false
  }
  # Account-wide deliberately also catches untagged shared/support/IPv4 charges.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_email]
  }
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_email]
  }
}

resource "aws_eip" "app" {
  domain     = "vpc"
  instance   = aws_instance.app.id
  depends_on = [aws_internet_gateway.this]
}
