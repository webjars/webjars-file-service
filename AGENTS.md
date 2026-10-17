# AGENTS.md: webjars-file-service

## Agent tooling

- Follow the `zen-of-projects` Skill (extract with `./sbt extractSkillsJars` into the gitignored `.kiro/skills/`); this file records only project-specific facts and exceptions.
- MCP server `sbt-mcp-webjars-file-service` (sbt-mcp) listens on `http://127.0.0.1:5115/`. Kiro uses the HTTP entry in `.kiro/settings/mcp.json`; start sbt first. Claude Code uses `.mcp.json`, which runs `.claude/sbt-mcp-stdio.sh` (approved in `.claude/settings.json`). That stdio bridge starts a foreground sbt in cloud sessions (`CLAUDE_CODE_REMOTE=true`), and locally only connects to an sbt you already started. Its tools are deferred: load them with ToolSearch (search `sbt-mcp-webjars-file-service`). Diagnostics go to `/tmp/sbt-mcp-stdio.log` and `/tmp/sbt-mcp-server.log`.
- Maintenance routine: `.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.

## Storage and development

- Webjar files are stored in S3 (`ObjectStore` / `WebJarStore`). On a miss the GAV takes one of 16 upload slots (all busy → 503 + `Retry-After`); its jar is downloaded to local staging (`StagedJar`), served from there while every webjar file plus a `manifests/<g>/<a>/<v>.json` marker is uploaded, then deleted; afterwards the GAV is served from S3.
- Production (`App`) requires `S3_BUCKET` and `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; `AWS_REGION` defaults to `us-east-1`.
- `./sbt Test/run` starts `AppTest`: it uses real S3 when `S3_BUCKET` and `AWS_ACCESS_KEY_ID` are set, otherwise a Floci (`floci/floci:2.3.0`) container simulating S3. Docker is required for that and for `S3ObjectStoreSpec` / `WebJarStoreSpec`.
- AWS resources (bucket + IAM user) are defined in `infra/aws.pkl` (cloudformation-pkl); `infra/aws.yaml` is generated from it (`cd infra && pkl eval aws.pkl -f yaml -o aws.yaml`) and committed. `infra/deploy.sh <local|prod> [--rotate]` deploys stack `webjars-file-service-<env>` in us-east-1 and creates/rotates the access key outside CloudFormation: `prod` sets `S3_BUCKET`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` on the Heroku app (restarts it); `local` writes the gitignored `.env.local`. To run against the local bucket: `set -a; . ./.env.local; set +a; ./sbt shutdown; ./sbt Test/run` (the sbt daemon must restart to see the new env).
