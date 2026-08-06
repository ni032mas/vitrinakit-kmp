.PHONY: help verify security test sample docs release-dry-run xcframework install-git-hooks clean

help:
	@printf '%s\n' 'VitrinaKit KMP SDK commands:'
	@printf '%s\n' '  make verify             Run security checks and release dry-run'
	@printf '%s\n' '  make security           Run secret and optional filesystem scanners'
	@printf '%s\n' '  make test               Run all SDK unit tests'
	@printf '%s\n' '  make sample             Compile and inspect Android flavor variants'
	@printf '%s\n' '  make docs               Generate Dokka API docs'
	@printf '%s\n' '  make release-dry-run    Build local Maven/KMP artifacts'
	@printf '%s\n' '  make xcframework        Build the iOS XCFramework'
	@printf '%s\n' '  make install-git-hooks  Install repository-managed git hooks'

verify: security release-dry-run

security:
	scripts/security-gate.sh

test:
	./gradlew test

sample:
	./gradlew verifyAndroidFlavorSample

docs:
	./gradlew :vitrinakit-core:dokkaGenerate :vitrinakit-googleplay:dokkaGenerate :vitrinakit-hosted:dokkaGenerate :vitrinakit-rustore:dokkaGenerate

release-dry-run:
	./gradlew verifyReleaseArtifacts

xcframework:
	./gradlew assembleVitrinaKitXCFramework

install-git-hooks:
	scripts/install-git-hooks.sh

clean:
	./gradlew clean
