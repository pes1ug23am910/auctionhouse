data "aws_iam_policy_document" "ec2_trust" {

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }

  }


}

resource "aws_iam_role" "host" {

  for_each           = toset(["app", "dependency"])
  name               = "${local.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json

}

resource "aws_iam_instance_profile" "host" {

  for_each = aws_iam_role.host
  name     = each.value.name
  role     = each.value.name

}

resource "aws_iam_role_policy_attachment" "ssm" {

  for_each   = aws_iam_role.host
  role       = each.value.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"

}

data "aws_iam_policy_document" "host" {

  for_each = toset(["app", "dependency"])
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = each.key == "app" ? [aws_ecr_repository.service["app"].arn, aws_ecr_repository.service["gateway"].arn] : [aws_ecr_repository.service["warehouse"].arn]
  }

  statement {
    actions   = ["ssm:GetParameter"]
    resources = [for kind in(each.key == "app" ? ["runtime", "migration"] : ["warehouse", "grafana"]) : "arn:aws:ssm:${var.region}:${var.account_id}:parameter${local.parameter_prefix}/${kind}"]
  }

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.service[each.key].arn}:*"]
  }


}

resource "aws_iam_role_policy" "host" {

  for_each = aws_iam_role.host
  name     = "project-runtime"
  role     = each.value.id
  policy   = data.aws_iam_policy_document.host[each.key].json

}

data "aws_iam_policy_document" "github_trust" {

  statement {

    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:${var.github_ref}"]
    }


  }


}

resource "aws_iam_role" "deploy" {

  name                 = "${local.name}-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_trust.json
  max_session_duration = 3600

}

data "aws_iam_policy_document" "deploy" {
  statement {
    actions   = ["ssm:GetDocument"]
    resources = [aws_ssm_document.release.arn, aws_ssm_document.inspect.arn]
  }

  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages", "ecr:DescribeImageScanFindings"]
    resources = [for repo in aws_ecr_repository.service : repo.arn]
  }

  statement {
    actions   = ["ssm:SendCommand"]
    resources = concat([aws_ssm_document.release.arn, aws_ssm_document.inspect.arn, aws_instance.dependency.arn], aws_instance.app[*].arn)
  }

  statement {
    actions   = ["ssm:GetCommandInvocation", "ec2:DescribeInstances", "elasticloadbalancing:DescribeTargetHealth", "elasticloadbalancing:DescribeLoadBalancers", "elasticloadbalancing:DescribeTags", "elasticloadbalancing:DescribeTargetGroups", "elasticloadbalancing:DescribeTargetGroupAttributes", "elasticloadbalancing:DescribeListeners", "elasticloadbalancing:DescribeRules", "rds:DescribeDBInstances", "rds:ListTagsForResource", "cloudwatch:GetMetricStatistics"]
    resources = ["*"]
  }

  statement {
    actions   = ["elasticloadbalancing:DeregisterTargets", "elasticloadbalancing:RegisterTargets"]
    resources = [for target in aws_lb_target_group.service : target.arn]
  }


}

resource "aws_iam_role_policy" "deploy" {

  name   = "project-delivery"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json

}

resource "aws_ssm_document" "release" {

  name            = "${local.name}-release"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({

    schemaVersion = "2.2", description = "Bounded immutable full-stack release; fixed installed implementation only"
    parameters = {

      Mode = {
        type = "String", allowedValues = ["migrate", "deploy", "rollback", "deactivate", "dependencies", "warehouse"], interpolationType = "ENV_VAR"
      }

      AppDigest = {
        type = "String", allowedPattern = "^sha256:[a-f0-9]{64}$", interpolationType = "ENV_VAR"
      }

      GatewayDigest = {
        type = "String", allowedPattern = "^sha256:[a-f0-9]{64}$", interpolationType = "ENV_VAR"
      }

      WarehouseDigest = {
        type = "String", allowedPattern = "^sha256:[a-f0-9]{64}$", interpolationType = "ENV_VAR"
      }

      CutId = {
        type = "String", allowedPattern = "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$", interpolationType = "ENV_VAR"
      }

      Compatibility = {
        type = "String", allowedValues = ["schema-compatible"], interpolationType = "ENV_VAR"
      }


    }

    mainSteps = [{
      action = "aws:runShellScript", name = "release", inputs = {
        timeoutSeconds = "900", runCommand = ["/opt/auctionhouse/full/release.sh \"$SSM_Mode\" \"$SSM_AppDigest\" \"$SSM_GatewayDigest\" \"$SSM_WarehouseDigest\" \"$SSM_CutId\" \"$SSM_Compatibility\""]
      }

      }
    ]

    }
  )

}

resource "aws_ssm_document" "inspect" {

  name            = "${local.name}-inspect"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2", description = "Read-only allowlisted runtime identity and configuration; no environment dump", mainSteps = [{
      action = "aws:runShellScript", name = "inspect", inputs = {
        timeoutSeconds = "60", runCommand = ["python3 /opt/auctionhouse/full/inspect-runtime.py"]
      }

      }
    ]
    }
  )

}

data "aws_iam_policy_document" "bootstrap" {

  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.this.master_user_secret[0].secret_arn]
  }

  statement {
    actions   = ["ssm:GetParameter", "ssm:PutParameter", "ssm:AddTagsToResource"]
    resources = [for kind in ["runtime", "migration", "warehouse", "grafana"] : "arn:aws:ssm:${var.region}:${var.account_id}:parameter${local.parameter_prefix}/${kind}"]
  }

  statement {
    actions   = ["ssm:StartSession"]
    resources = [aws_instance.dependency.arn, "arn:aws:ssm:${var.region}::document/AWS-StartPortForwardingSessionToRemoteHost"]
  }

  statement {
    actions   = ["ssm:TerminateSession", "ssm:ResumeSession", "ssmmessages:OpenDataChannel"]
    resources = ["arn:aws:ssm:${var.region}:${var.account_id}:session/$${aws:userid}-*"]
  }


}
