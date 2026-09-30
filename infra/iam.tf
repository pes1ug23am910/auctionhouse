data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}
resource "aws_iam_role" "app" {
  name               = "${local.name}-host"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}
resource "aws_iam_instance_profile" "app" {
  name = local.name
  role = aws_iam_role.app.name
}
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
data "aws_iam_policy_document" "host" {
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = [aws_ecr_repository.app.arn]
  }
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [for kind in ["runtime", "migration"] : "arn:aws:ssm:${var.region}:${var.account_id}:parameter${local.parameter_prefix}/${kind}"]
  }
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.app.arn}:*"]
  }
}
resource "aws_iam_role_policy" "host" {
  name   = "project-runtime"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.host.json
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
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages", "ecr:DescribeImageScanFindings"]
    resources = [aws_ecr_repository.app.arn]
  }
  statement {
    actions   = ["ssm:SendCommand"]
    resources = [aws_ssm_document.release.arn, aws_instance.app.arn]
  }
  statement {
    actions   = ["ssm:GetCommandInvocation"]
    resources = ["*"]
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
    schemaVersion = "2.2"
    description   = "Deploy one immutable auctionhouse image through the reviewed host command"
    parameters = {
      Mode          = { type = "String", allowedValues = ["deploy", "rollback"], interpolationType = "ENV_VAR" }
      Digest        = { type = "String", allowedPattern = "^sha256:[a-f0-9]{64}$", interpolationType = "ENV_VAR" }
      Compatibility = { type = "String", allowedValues = ["schema-compatible"], interpolationType = "ENV_VAR" }
    }
    mainSteps = [{ action = "aws:runShellScript", name = "release", inputs = { timeoutSeconds = "900", runCommand = ["/opt/auctionhouse/release.sh \"$SSM_Mode\" \"$SSM_Digest\" \"$SSM_Compatibility\""] } }]
  })
}
data "aws_iam_policy_document" "bootstrap_trust" {
  count = var.create_bootstrap_role ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = [var.bootstrap_principal_arn]
    }
    condition {
      test     = "Bool"
      variable = "aws:MultiFactorAuthPresent"
      values   = ["true"]
    }
  }
}
resource "aws_iam_role" "bootstrap" {
  count                = var.create_bootstrap_role ? 1 : 0
  name                 = "${local.name}-db-bootstrap"
  assume_role_policy   = data.aws_iam_policy_document.bootstrap_trust[0].json
  max_session_duration = 3600
}
data "aws_iam_policy_document" "bootstrap" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.this.master_user_secret[0].secret_arn]
  }
  statement {
    actions   = ["ssm:GetParameter", "ssm:PutParameter", "ssm:AddTagsToResource"]
    resources = [for kind in ["runtime", "migration"] : "arn:aws:ssm:${var.region}:${var.account_id}:parameter${local.parameter_prefix}/${kind}"]
  }
  statement {
    actions   = ["ssm:StartSession"]
    resources = [aws_instance.app.arn, "arn:aws:ssm:${var.region}::document/AWS-StartPortForwardingSessionToRemoteHost"]
  }
  statement {
    actions   = ["ssm:TerminateSession", "ssm:ResumeSession", "ssmmessages:OpenDataChannel"]
    resources = ["arn:aws:ssm:${var.region}:${var.account_id}:session/$${aws:userid}-*"]
  }
}
resource "aws_iam_role_policy" "bootstrap" {
  count  = var.create_bootstrap_role ? 1 : 0
  name   = "one-time-database-bootstrap"
  role   = aws_iam_role.bootstrap[0].id
  policy = data.aws_iam_policy_document.bootstrap.json
}
