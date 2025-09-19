#!/bin/bash
# ==============================================================================
# RELEASE SCRIPT FOR KESTRA PLUGINS
#
# This script automates the release process for Kestra plugin repositories.
# It supports MAJOR, MINOR, and PATCH version releases based on Git branches.
#
# MAJOR and MINOR releases (e.g., 2.0.0 or 1.3.0):
# - Performed from the default branch (main or master)
# - Creates a new release branch if not already present (e.g., releases/v1.3.x)
# - Runs `./gradlew release`, which automatically creates a Git tag
# - Updates gradle.properties to the NEXT snapshot version (e.g., 1.4.0-SNAPSHOT)
#
# PATCH releases (e.g., 1.3.2):
# - Performed directly on an existing maintenance branch (e.g., releases/v1.3.x)
# - Updates gradle.properties to the patch version
# - Commits the change
# - Creates an annotated Git tag (e.g., v1.3.2)
# - Pushes the commit and the tag
#
# USAGE:
#   ./release.sh <releaseVersion> [nextVersion]
#
# EXAMPLES:
#   # MAJOR release (with next version)
#   ./release.sh 2.0.0 2.1.0-SNAPSHOT
#
#   # MINOR release (with next version)
#   ./release.sh 1.3.0 1.4.0-SNAPSHOT
#
#   # PATCH release (no next version)
#   ./release.sh 1.3.2
# ==============================================================================

set -euo pipefail

RELEASE_VERSION=$1
NEXT_VERSION=${2:-}

echo "📦 Release Version: $RELEASE_VERSION"
echo "📦 Next Version: $NEXT_VERSION"

# Tag to be created
TAG="v${RELEASE_VERSION}"

# Extract X.Y for the maintenance branch
BASE_VERSION=$(echo "$RELEASE_VERSION" | grep -Eo '^[0-9]+\.[0-9]+')
RELEASE_BRANCH="releases/v${BASE_VERSION}.x"

# Detect the default branch (main or master)
DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD | sed 's@^refs/remotes/origin/@@')

# Check if the tag already exists
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ Tag '$TAG' already exists. Aborting."
  exit 1
fi

# If NEXT_VERSION is not provided, this is a PATCH release
if [[ -z "$NEXT_VERSION" ]]; then
  echo "🛠 Detected PATCH release mode on branch $RELEASE_BRANCH"

  # Ensure the release branch exists remotely
  if ! git ls-remote --heads origin "$RELEASE_BRANCH" &>/dev/null; then
    echo "❌ Branch $RELEASE_BRANCH does not exist."
    exit 1
  fi

  # Checkout and update the release branch
  git checkout "$RELEASE_BRANCH"
  git pull origin "$RELEASE_BRANCH"

  echo "🔧 Updating gradle.properties with version=$RELEASE_VERSION"
  sed -i "s/^version=.*/version=${RELEASE_VERSION}/" gradle.properties

  git add gradle.properties
  git commit -m "chore(version): update to version '${RELEASE_VERSION}'"

  echo "🏷 Creating annotated tag: $TAG"
  git tag -a "$TAG" -m "$TAG"

  echo "📤 Pushing commit and tag"
  git push origin "$RELEASE_BRANCH"
  git push origin "$TAG"

  echo "✅ Patch release $RELEASE_VERSION completed!"
else
  echo "🚀 Detected MAJOR or MINOR release mode on branch $DEFAULT_BRANCH"

  # Checkout and pull the default branch
  git checkout "$DEFAULT_BRANCH"
  git pull origin "$DEFAULT_BRANCH"

  # Create the release branch if it doesn't exist yet
  if ! git ls-remote --heads origin "$RELEASE_BRANCH" &>/dev/null; then
    git checkout -b "$RELEASE_BRANCH"
    git push origin "$RELEASE_BRANCH"
  else
    echo "ℹ️ Branch '$RELEASE_BRANCH' already exists."
  fi

  # Return to the default branch for the actual release
  git checkout "$DEFAULT_BRANCH"

  echo "🧪 Running Gradle release..."
  echo "ℹ️ Note: './gradlew release' will automatically create and push the Git tag '$TAG'"

  # Perform the Gradle release (this creates and pushes the tag)
  ./gradlew release \
    -Prelease.useAutomaticVersion=true \
    -Prelease.releaseVersion="$RELEASE_VERSION" \
    -Prelease.newVersion="$NEXT_VERSION"

  # Update gradle.properties with the next snapshot version
  echo "📝 Updating gradle.properties with next snapshot version: $NEXT_VERSION"
  sed -i "s/^version=.*/version=${NEXT_VERSION}/" gradle.properties

  git add gradle.properties
  git commit -m "chore(version): prepare for next development iteration (${NEXT_VERSION})"
  git push origin "$DEFAULT_BRANCH"

  echo "✅ MAJOR or MINOR release $RELEASE_VERSION completed!"
fi
