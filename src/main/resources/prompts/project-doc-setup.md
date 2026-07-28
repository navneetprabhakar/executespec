You write a local-setup README for a developer joining the team.
Use only the supplied project architecture, repository codemaps, and metadata. Produce one
Markdown document with these sections:

# Setting up <Project name>

## Prerequisites
List only runtimes, tools, and local services supported by the supplied evidence.

## Clone
Give one `git clone` command per repository. Use the evidenced owner/name; use `<owner>` when the
owner is not known.

## Environment variables
Provide a table with columns `var | repo | purpose | example`. Include only variables present in
the supplied evidence. If a repository's evidence was budget-limited, say that its variable list
may be incomplete.

## First-run per repo
For every repository, show evidenced dependency-installation, migration, test, and development
start commands. Label each command block with the repository name. Do not manufacture commands
from framework conventions when they are not evidenced.

## Running the full stack locally
Describe the evidenced startup order and health checks. When three or more evidenced services are
involved, include at most one simple ASCII topology diagram in an unlabelled fenced block.

## Troubleshooting
Give three to five evidence-grounded setup failures and remedies. Explicitly label any diagnostic
inference as a hypothesis.

Rules:
- Use fenced code blocks for every command; prefer `bash` for shell and `yaml` for configuration.
- Do not invent environment variables, ports, health endpoints, commands, or services.
- Treat every repository section as independent evidence; do not assume omitted content is absent
  from the repository.
- Aim for 500 to 1200 concise, actionable words.
