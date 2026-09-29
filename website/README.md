# AgentForge Documentation

AgentForge 的 Docusaurus 文档站，包含中英文内容、v1.0 / Next 版本切换，以及 GitHub Pages 自动部署配置。

## 本地开发

```bash
cd website
npm ci
npm start
```

默认地址为 `http://localhost:3000/AgentForge/`。英文开发模式可运行 `npm run start:en`。

## 生产构建

```bash
npm run build
npm run serve
```

需要在本地同时预览中文和英文并测试语言切换时，使用：

```bash
npm run preview
```

访问 `http://localhost:3000/AgentForge/`，通过导航栏语言菜单切换中文与 English。

## 文档结构

- `docs/`：当前开发版（Next）的中文文档；
- `i18n/en/`：英文界面与文档；
- `versioned_docs/version-1.0/`：v1.0 中文快照；
- `versioned_sidebars/`：各版本侧边栏；
- `versions.json`：已发布版本列表。

发布新版本时，在 `website/` 中运行：

```bash
npm run docs:version -- 1.1
```

推送到 `main` 后，根目录 `.github/workflows/docs-pages.yml` 会构建双语站点并发布到 GitHub Pages。
