locals {

  name             = "auctionhouse-${var.session_id}-full"
  parameter_prefix = "/auctionhouse/${var.session_id}/full"
  tags = {
    Project = "auctionhouse", SessionId = var.session_id, Owner = var.owner, ExpiresAt = var.expires_at, ManagedBy = "Terraform", Topology = "full"
  }

  runtime_settings = {

    javaHeap    = "-Xms256m -Xmx512m"
    poolSize    = var.application_pool_size
    cache       = "memcached"
    session     = "postgresql-jdbc"
    idempotency = "postgresql"
    broker      = "redpanda-single-node-fsync"
    telemetry   = true
    sse         = var.gateway_events ? "node-gateway" : "java"
    sticky      = false

  }

  runtime_configuration_sha256 = sha256(jsonencode(local.runtime_settings))

}

resource "aws_vpc" "this" {

  cidr_block           = "10.74.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

}

resource "aws_subnet" "public" {

  count                   = 2
  vpc_id                  = aws_vpc.this.id
  cidr_block              = "10.74.${count.index + 1}.0/24"
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

}

resource "aws_subnet" "database" {

  count                   = 2
  vpc_id                  = aws_vpc.this.id
  cidr_block              = "10.74.${count.index + 10}.0/24"
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false

}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
}

resource "aws_route_table" "public" {

  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }


}

resource "aws_route_table" "database" {
  vpc_id = aws_vpc.this.id
}

resource "aws_route_table_association" "public" {

  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id

}

resource "aws_route_table_association" "database" {

  count          = 2
  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id

}

resource "aws_security_group" "service" {

  for_each = toset(["alb", "app", "dependency", "database"])
  name     = "${local.name}-${each.key}"
  vpc_id   = aws_vpc.this.id

}

resource "aws_vpc_security_group_ingress_rule" "https" {

  security_group_id = aws_security_group.service["alb"].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443

}

resource "aws_vpc_security_group_ingress_rule" "target" {

  for_each                     = toset(["8080", "3001"])
  security_group_id            = aws_security_group.service["app"].id
  referenced_security_group_id = aws_security_group.service["alb"].id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)

}

resource "aws_vpc_security_group_egress_rule" "target" {

  for_each                     = toset(["8080", "3001"])
  security_group_id            = aws_security_group.service["alb"].id
  referenced_security_group_id = aws_security_group.service["app"].id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)

}

resource "aws_vpc_security_group_ingress_rule" "dependency" {

  for_each                     = toset(["9092", "11211", "4318"])
  security_group_id            = aws_security_group.service["dependency"].id
  referenced_security_group_id = aws_security_group.service["app"].id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)

}

resource "aws_vpc_security_group_egress_rule" "dependency" {

  for_each                     = toset(["9092", "11211", "4318"])
  security_group_id            = aws_security_group.service["app"].id
  referenced_security_group_id = aws_security_group.service["dependency"].id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)

}

resource "aws_vpc_security_group_ingress_rule" "database" {

  for_each                     = toset(["app", "dependency"])
  security_group_id            = aws_security_group.service["database"].id
  referenced_security_group_id = aws_security_group.service[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432

}

resource "aws_vpc_security_group_egress_rule" "database" {

  for_each                     = toset(["app", "dependency"])
  security_group_id            = aws_security_group.service[each.key].id
  referenced_security_group_id = aws_security_group.service["database"].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432

}

resource "aws_vpc_security_group_egress_rule" "https" {

  for_each          = toset(["app", "dependency"])
  security_group_id = aws_security_group.service[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443

}

resource "aws_db_subnet_group" "this" {

  name       = local.name
  subnet_ids = aws_subnet.database[*].id

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

resource "aws_cloudwatch_log_group" "service" {

  for_each          = toset(["app", "dependency"])
  name              = "/auctionhouse/${var.session_id}/full/${each.key}"
  retention_in_days = 7

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
  vpc_security_group_ids          = [aws_security_group.service["database"].id]
  publicly_accessible             = false
  multi_az                        = false
  backup_retention_period         = 1
  auto_minor_version_upgrade      = false
  allow_major_version_upgrade     = false
  deletion_protection             = !var.allow_destroy
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-final"
  copy_tags_to_snapshot           = true
  delete_automated_backups        = true
  enabled_cloudwatch_logs_exports = ["postgresql"]

}

resource "aws_ecr_repository" "service" {

  for_each             = toset(["app", "gateway", "warehouse"])
  name                 = "${local.name}-${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false
  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }


}

resource "aws_lb" "this" {

  name                       = substr(local.name, 0, 32)
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.service["alb"].id]
  subnets                    = aws_subnet.public[*].id
  idle_timeout               = 120
  drop_invalid_header_fields = true
  enable_deletion_protection = !var.allow_destroy

}

resource "aws_lb_target_group" "service" {

  for_each = {
    app = 8080, gateway = 3001
  }

  name                          = "${substr(local.name, 0, 23)}-${each.key}"
  target_type                   = "instance"
  port                          = each.value
  protocol                      = "HTTP"
  vpc_id                        = aws_vpc.this.id
  deregistration_delay          = 30
  load_balancing_algorithm_type = "round_robin"
  stickiness {
    type    = "lb_cookie"
    enabled = false
  }

  health_check {

    path                = each.key == "app" ? "/actuator/health/readiness" : "/health"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2

  }


}

resource "aws_lb_listener" "https" {

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      status_code  = "403"
      message_body = "Unrecognized application host"
    }

  }


}

resource "aws_lb_listener_rule" "app" {

  listener_arn = aws_lb_listener.https.arn
  priority     = 100
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service["app"].arn
  }

  condition {
    host_header {
      values = [var.domain_name]
    }

  }


}

resource "aws_lb_listener_rule" "events" {

  count        = var.gateway_events ? 1 : 0
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service["gateway"].arn
  }

  condition {
    path_pattern {
      values = ["/api/auctions/*/events"]
    }

  }

  condition {
    host_header {
      values = [var.domain_name]
    }

  }


}

resource "aws_ebs_volume" "dependencies" {

  availability_zone = var.availability_zones[0]
  size              = 32
  type              = "gp3"
  encrypted         = true
  tags = merge(local.tags, {
    Name = "${local.name}-dependencies"
    }
  )

}

resource "aws_volume_attachment" "dependencies" {

  device_name                    = "/dev/sdf"
  volume_id                      = aws_ebs_volume.dependencies.id
  instance_id                    = aws_instance.dependency.id
  stop_instance_before_detaching = true

}

resource "aws_instance" "dependency" {

  ami                    = var.ami_id
  instance_type          = var.dependency_instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.service["dependency"].id]
  iam_instance_profile   = aws_iam_instance_profile.host["dependency"].name
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

  dynamic "credit_specification" {
    for_each = var.dependency_instance_type == "t3.large" ? [1] : []
    content {
      cpu_credits = "standard"
    }
  }

  user_data_replace_on_change = true
  user_data_base64 = base64gzip(templatefile("${path.module}/host-init.sh.tftpl", {
    bundle = base64gzip(jsonencode(merge(local.assets, {
      "host-config.json" = jsonencode(merge(local.host_common, {
        role = "dependency", dependencyVolumeId = aws_ebs_volume.dependencies.id
        }
      ))
      }
    )))
    }
  ))
  tags = merge(local.tags, {
    Name = "${local.name}-dependency", ServiceRole = "dependency"
    }
  )

}

resource "aws_instance" "app" {

  count                  = var.application_host_count
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public[count.index % 2].id
  vpc_security_group_ids = [aws_security_group.service["app"].id]
  iam_instance_profile   = aws_iam_instance_profile.host["app"].name
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
  user_data_base64 = base64gzip(templatefile("${path.module}/host-init.sh.tftpl", {
    bundle = base64gzip(jsonencode(merge(local.assets, {
      "host-config.json" = jsonencode(merge(local.host_common, {
        role = "app", ordinal = count.index, dependencyAddress = aws_instance.dependency.private_ip
        }
      ))
      }
    )))
    }
  ))
  tags = merge(local.tags, {
    Name = "${local.name}-app-${count.index}", ServiceRole = "app"
    }
  )

}

resource "aws_lb_target_group_attachment" "app" {

  count            = var.application_host_count
  target_group_arn = aws_lb_target_group.service["app"].arn
  target_id        = aws_instance.app[count.index].id
  port             = 8080

}

resource "aws_lb_target_group_attachment" "gateway" {

  count            = var.application_host_count
  target_group_arn = aws_lb_target_group.service["gateway"].arn
  target_id        = aws_instance.app[count.index].id
  port             = 3001

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
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
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
