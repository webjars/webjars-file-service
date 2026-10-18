# AGENTS.md: webjars-file-service

## Agent tooling

- Follow the `zen-of-projects` Skill (extract with `./sbta extractSkillsJars` into the gitignored `.kiro/skills/`); this file records only project-specific facts and exceptions.
- MCP server `sbta` (this build's `./sbta --mcp`, configured in `.mcp.json` and `.kiro/settings/mcp.json`, approved in `.claude/settings.json`): use it for all sbt work: `compile`, `test` (`full` for all), `run`, `check` after editing Scala 3 sources, `glob-search` / `inspect` / `symbol-location` for symbols, `sbt-task` for any other command. Its tools are deferred in Claude Code: load them with ToolSearch (search `sbta`). If it's unavailable, say so and use `./sbta <command>`. Logs: `project/target/sbta/mcp.log`.
- Maintenance routine: `.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.

## Build, Test & Dev Workflow

- Use the MCP server `sbta` (this build's `./sbta --mcp`) for all sbt work: build, test and run
  with `compile`, `test` (`tests` for specific classes, `full` for all), `run`, `clean` and
  `publish-local`; inspect the build with `projects`, `test-names`, `main-classes`, `dependencies` and
  `show`. Use `sbt-task` for any other command (e.g. `reload`) and `list-tasks` to find tasks. Don't run
  `sbt` or `./sbta` in a shell while the MCP tools are available; if the server is unavailable, say so and
  fall back to `./sbta <command>`.
- After editing Scala 3 sources, check them with `check` (pass the edited files: a fast type check
  without compiling; `"scope":"module"` after changing an API), then run `compile` and `test` before
  declaring the work done.
- For Scala symbols use `glob-search` (find or list), `inspect` (members and signatures) and
  `symbol-location` (source location) rather than text search or guessing APIs. JavaDoc/ScalaDoc
  lookups are the proxied javadocs.dev tools of the same server.

## Storage and development

- Webjar files are stored in S3 (`ObjectStore` / `WebJarStore`). On a miss the GAV takes one of 64 upload slots (all busy → 503 + `Retry-After`); its jar is downloaded to local staging (`StagedJar`), served from there while every webjar file plus a `manifests/<g>/<a>/<v>.json` marker is uploaded, then deleted; afterwards the GAV is served from S3.
- Production (`App`) requires `S3_BUCKET` and `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; `AWS_REGION` defaults to `us-east-1`.
- `./sbta Test/run` starts `AppTest`: it uses real S3 when `S3_BUCKET` and `AWS_ACCESS_KEY_ID` are set, otherwise a Floci (`floci/floci:2.3.0`) container simulating S3. Docker is required for that and for `S3ObjectStoreSpec` / `WebJarStoreSpec`.
- S3 access is our own client (`S3ObjectStore` over zio-http, `SigV4` signing; no AWS SDK). Floci can't validate SigV4 (it ignores the secret and doesn't re-encode the path like S3), so signing is covered by `SigV4Spec` (AWS's documented example) and the real-S3 suite in `S3ObjectStoreSpec`, which runs only when `S3_INTEGRATION=true`: `(set -a; . ./.env.local; set +a; export S3_INTEGRATION=true; ./sbta shutdown; ./sbta testFull)`. It writes small objects under `test/` in the local bucket, which a lifecycle rule expires after a day.
- AWS resources (bucket + IAM user) are defined in `infra/aws.pkl` (cloudformation-pkl); `infra/aws.yaml` is generated from it (`cd infra && pkl eval aws.pkl -f yaml -o aws.yaml`) and committed. `infra/deploy.sh <local|prod> [--rotate]` deploys stack `webjars-file-service-<env>` in us-east-1 and creates/rotates the access key outside CloudFormation: `prod` sets `S3_BUCKET`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` on the Heroku app (restarts it); `local` writes the gitignored `.env.local`. To run against the local bucket: `set -a; . ./.env.local; set +a; ./sbta shutdown; ./sbta Test/run` (the sbt daemon must restart to see the new env).
