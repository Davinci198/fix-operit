#!/usr/bin/env bash
# Backup script - salvează starea curentă înainte de revert
# Usage: bash backup-before-revert.sh

set -e

BACKUP_DIR="/sdcard/Operit/memorie-operit/backup/fix-operit"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_POINT="$BACKUP_DIR/backup_$TIMESTAMP"

log() {
  echo "[backup-before-revert] $*" >&2
}

fail() {
  log "ERROR: $*"
  exit 1
}

mkdir -p "$BACKUP_DIR"

log "Creating backup at: $BACKUP_POINT"

# Get current state info
CURRENT_COMMIT=$(git rev-parse --short HEAD)
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
CURRENT_TAG=$(git describe --tags --always 2>/dev/null || echo "none")

# Create backup metadata
cat > "$BACKUP_POINT.info" <<EOF
=== FIX-OPERIT BACKUP ===
Timestamp: $TIMESTAMP
Current Commit: $CURRENT_COMMIT
Current Branch: $CURRENT_BRANCH
Current Tag: $CURRENT_TAG

Git Status:
$(git status --short)

Recent Commits:
$(git log --oneline -5)
EOF

log "Backup info saved to: $BACKUP_POINT.info"

# Create bundle (lightweight git backup)
git bundle create "$BACKUP_POINT.bundle" --all || fail "Failed to create git bundle"

log "Git bundle saved to: $BACKUP_POINT.bundle"

# Tar the entire repo for full recovery
tar --exclude='.git' --exclude='.cxx' --exclude='build' \
    -czf "$BACKUP_POINT-files.tar.gz" . 2>/dev/null || true

log "Source files backed up to: $BACKUP_POINT-files.tar.gz"

# Summary
log ""
log "✅ BACKUP COMPLETE"
log "Location: $BACKUP_POINT"
log ""
log "To restore:"
log "  git bundle verify $BACKUP_POINT.bundle"
log "  git fetch $BACKUP_POINT.bundle"
log ""
