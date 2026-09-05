#!/bin/zsh
# The Swift engine as a command-line tool.
#
# `Shared/Engine/*.swift` and `Shared/BlobCodec.swift` import only Foundation, so they
# compile on a Mac with nothing but `swiftc` — into ONE module with the generators and
# verifiers in this directory. That is what lets a fixture be produced by the real iOS
# encoder, and a Kotlin-recorded runner trace be replayed through the real iOS runner,
# without an Xcode project, a simulator, or a commit on the iOS side.
#
#   ./build.sh            # builds build/oracle
#   build/oracle          # prints the command list
#
# GETAGRIP_IOS_TREE points at the iOS checkout whose WORKING TREE is the truth
# (default: the main checkout). Read-only: nothing here ever writes into it.
set -euo pipefail
cd "$(dirname "$0")"
IOS="${GETAGRIP_IOS_TREE:-$(cd ../../.. && pwd)}"
[[ -d "$IOS/Shared/Engine" ]] || { echo "No iOS engine at $IOS/Shared/Engine (set GETAGRIP_IOS_TREE)" >&2; exit 1; }
mkdir -p build
swiftc -O -module-name Oracle -suppress-warnings -o build/oracle \
  "$IOS"/Shared/Engine/*.swift "$IOS"/Shared/BlobCodec.swift \
  ./*.swift
echo "built build/oracle from $IOS"
