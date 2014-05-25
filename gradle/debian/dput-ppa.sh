#!/bin/bash

SIGNKEYID="651D12BD"

SCRIPTDIR="$( cd "$( dirname "$0" )" && pwd )"
REPODIR="$SCRIPTDIR/../.."

if [ -n "$TRAVIS_PULL_REQUEST" -a "$TRAVIS_PULL_REQUEST" != "false" ]; then
	echo "NOTE: Skipping GPG stuff. This job is a PULL REQUEST."
	exit 0
fi

# Choose PPA
IS_RELEASE=$(git log -n 1 --pretty=%d HEAD | grep origin/master)

if [ -n "$IS_RELEASE" ]; then 
	#TARGET_PPA="syncany/release"
	TARGET_PPA="ppa:syncany/release-test"
else
	#TARGET_PPA="syncany/snapshot"
	TARGET_PPA="ppa:syncany/snapshot-test"
fi

# Test files
PPA_FILE_COUNT=$(ls $REPODIR/build/debian/*.{dsc,changes,build,tar.gz} | wc -l)

if [ "4" != "$PPA_FILE_COUNT" ]; then
	echo "ERROR: Unexpected files in debian build dir."
	
	ls $REPODIR/build/debian/
	exit 1
fi

# Run dput
cd $REPODIR/build/debian/
dput --config $REPODIR/gradle/debian/dput.cf $TARGET_PPA *.changes