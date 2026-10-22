.PHONY: configure-release deploy-gh-pages deploy-page

configure-release:
	node scripts/configure-android-release.mjs

deploy-gh-pages:
	node scripts/release-next-android.mjs

deploy-page:
	node scripts/deploy-gh-pages.mjs
