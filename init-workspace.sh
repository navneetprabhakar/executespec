#!/usr/bin/env bash
# init-workspace.sh — Run once before the first agent run.
# Creates the workspace git repo and .agent-context/ structure the harness expects.
#
# Usage:
#   chmod +x init-workspace.sh
#   ./init-workspace.sh [workspace-dir]
#
# Default workspace dir: ./workspace (matches agent.workspace in application.yml)

set -euo pipefail

WORKSPACE_DIR="${1:-workspace}"

echo "Initialising agent workspace at: $WORKSPACE_DIR"

# 1. Create workspace git repo
mkdir -p "$WORKSPACE_DIR"
cd "$WORKSPACE_DIR"

if [ ! -d ".git" ]; then
  git init
  git checkout -b main
  git commit --allow-empty -m "chore: init agent workspace"
  echo "  Git repo initialised on branch 'main'"
else
  echo "  Git repo already exists — skipping init"
fi

# 2. Create .agent-context/ with seed files
mkdir -p .agent-context
mkdir -p .agent-screenshots

# Empty JSON seed files — harness overwrites these on each run
[ -f ".agent-context/file-locks.json" ]       || echo '{}' > .agent-context/file-locks.json
[ -f ".agent-context/shared-contracts.json" ] || echo '{}' > .agent-context/shared-contracts.json
[ -f ".agent-context/task-assignments.json" ] || echo '{}' > .agent-context/task-assignments.json
[ -f ".agent-context/decisions.md" ]          || echo '# Decisions' > .agent-context/decisions.md

# 3. Copy CODEBASE_CONTEXT.md template if not already present
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$SCRIPT_DIR/src/main/resources/templates/CODEBASE_CONTEXT.md.template"
CONTEXT_FILE=".agent-context/CODEBASE_CONTEXT.md"

if [ ! -f "$CONTEXT_FILE" ]; then
  if [ -f "$TEMPLATE" ]; then
    cp "$TEMPLATE" "$CONTEXT_FILE"
    echo "  CODEBASE_CONTEXT.md created from template — fill in the placeholders"
  else
    echo "  WARNING: template not found at $TEMPLATE — create $CONTEXT_FILE manually"
  fi
else
  echo "  CODEBASE_CONTEXT.md already exists — skipping"
fi

# 4. Add .gitignore
cat > .gitignore << 'GITIGNORE'
target/
*.class
*.jar
.DS_Store
node_modules/
__pycache__/
*.pyc
.agent-screenshots/
GITIGNORE

# 5. Commit initial structure
git add .
git diff --cached --quiet || git commit -m "chore: add agent context structure"

cd ..

# 6. Verify ANTHROPIC_API_KEY is set
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo ""
  echo "  WARNING: ANTHROPIC_API_KEY is not set."
  echo "  Export it before running: export ANTHROPIC_API_KEY=sk-ant-..."
else
  echo "  ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:0:10}... (set)"
fi

echo ""
echo "Workspace ready at: $WORKSPACE_DIR/"
echo ""
echo "Next steps:"
echo "  1. Edit $WORKSPACE_DIR/.agent-context/CODEBASE_CONTEXT.md with your project details"
echo "  2. Run: mvn spring-boot:run (or java -jar target/agentteam.jar)"
