// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Where the site is served from. The Pages workflow sets both from the repository's Pages settings,
// so nothing changes here when the repository moves to another owner or gets a custom domain.
const site = process.env.SITE_URL ?? 'https://anorys.github.io';
const base = process.env.BASE_PATH ?? '/chainrunner';
// The repository the "Edit page" and GitHub links point to (owner/name).
const repository = process.env.REPOSITORY ?? 'anorys/chainrunner';

export default defineConfig({
	site,
	base,
	trailingSlash: 'always',
	integrations: [
		starlight({
			title: 'ChainRunner',
			description:
				'ChainRunner runs chains of API calls, each API with its own OAuth2 token, waiting on PostgreSQL queries and downloading documents between them.',
			logo: {
				light: './src/assets/logo-light.svg',
				dark: './src/assets/logo-dark.svg',
				alt: 'Anorys faceted hexagon',
			},
			favicon: '/favicon.svg',
			// English at the root, French under /fr/. A page missing in French shows the English one.
			defaultLocale: 'root',
			locales: {
				root: { label: 'English', lang: 'en' },
				fr: { label: 'Français', lang: 'fr' },
			},
			social: [{ icon: 'github', label: 'GitHub', href: `https://github.com/${repository}` }],
			editLink: { baseUrl: `https://github.com/${repository}/edit/main/docs/` },
			lastUpdated: true,
			customCss: [
				'@fontsource/signika/500.css',
				'@fontsource/signika/600.css',
				'@fontsource/heebo/400.css',
				'@fontsource/heebo/500.css',
				'@fontsource/heebo/600.css',
				'@fontsource/jetbrains-mono/400.css',
				'./src/styles/anorys.css',
			],
			sidebar: [
				{
					label: 'Getting started',
					translations: { fr: 'Démarrer' },
					items: [
						{ slug: 'getting-started/introduction' },
						{ slug: 'getting-started/installation' },
						{ slug: 'getting-started/quick-start' },
					],
				},
				{
					label: 'Guides',
					translations: { fr: 'Guides' },
					items: [
						{ slug: 'guides/terminal-ui' },
						{ slug: 'guides/apis-and-tokens' },
						{ slug: 'guides/databases' },
						{ slug: 'guides/scripts-and-ci' },
					],
				},
				{
					label: 'Reference',
					translations: { fr: 'Référence' },
					items: [
						{ slug: 'reference/configuration' },
						{ slug: 'reference/templates' },
						{ slug: 'reference/command-line' },
					],
				},
				{
					label: 'Development',
					translations: { fr: 'Développement' },
					items: [
						{ slug: 'development/building' },
						{ slug: 'development/releasing' },
						{ slug: 'development/step-types' },
						{ slug: 'development/tui-style-guide' },
					],
				},
			],
		}),
	],
});
