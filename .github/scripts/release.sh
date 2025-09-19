#!/bin/bash
set -euo pipefail

RELEASE_VERSION=$1
NEXT_VERSION=$2

echo "📦 Release Version: $RELEASE_VERSION"
echo "📦 Next Version: $NEXT_VERSION"

# Determine tag
TAG="v${RELEASE_VERSION}"

# Check if tag already exists
if git rev-parse "$TAG" >/dev/null 2>&1; then
  echo "❌ Tag '$TAG' already exists. Aborting."
  exit 1
fi

# Compute release branch
BASE_VERSION=$(echo "$RELEASE_VERSION" | grep -Eo '^[0-9]+\.[0-9]+')
RELEASE_BRANCH="releases/v${BASE_VERSION}.x"

echo "🌿 Creating release branch: $RELEASE_BRANCH"

# Create the release branch if it doesn't exist remotely
if ! git ls-remote --exit-code --heads origin "$RELEASE_BRANCH" > /dev/null; then
  git checkout -b "$RELEASE_BRANCH"
  git push origin "$RELEASE_BRANCH"
else
  echo "ℹ️ Branch '$RELEASE_BRANCH' already exists on remote."
fi

# Switch back to default branch
DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD | sed 's@^refs/remotes/origin/@@')
git checkout "$DEFAULT_BRANCH"

# Perform gradle release
echo "🚀 Running gradle release..."
./gradlew release \
  -Prelease.useAutomaticVersion=true \
  -Prelease.releaseVersion="$RELEASE_VERSION" \
  -Prelease.newVersion="$NEXT_VERSION"

echo "✅ Release completed!"
