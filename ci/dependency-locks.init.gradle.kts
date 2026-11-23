// Used only by ci/check.py osv: turns on dependency locking for every project of the build (and its included
// builds), so `dependencies --write-locks` writes gradle.lockfile files with the fully resolved graph that
// OSV-Scanner can read. The repo itself keeps no lockfiles; they are generated, scanned and deleted.
allprojects { dependencyLocking { lockAllConfigurations() } }
