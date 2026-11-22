"use strict";

const byId = (id) => document.getElementById(id);
const pairScreen = byId("pair-screen");
const workspace = byId("workspace");
const pairCode = byId("pair-code");
const pairSubmit = byId("pair-submit");
const searchQuery = byId("search-query");
const searchSubmit = byId("search-submit");
const generateAnswer = byId("generate-answer");
const imageQuery = byId("image-query");
const imageSubmit = byId("image-submit");
const imageDialog = byId("image-dialog");
const imagePreview = byId("image-preview");
const imagePreviewArticle = byId("image-preview-article");
const articleDialog = byId("article-dialog");
const removeDialog = byId("remove-dialog");
const removeConfirm = byId("remove-confirm");
const removeCancel = byId("remove-cancel");
const panelFocus = new Map([["library", byId("library-title")], ["search", searchQuery], ["images", imageQuery]]);
const languageNames = { pt: "Português", en: "Inglês" };
const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "medium", timeZone: "UTC" });
const networkError = "O WikiAI Offline está indisponível. Confira a conexão e a opção Acesso pela rede no Android.";

let session = null;
let catalogVersion = 0;
let articleVersion = 0;
let imageSearchVersion = 0;
let imageSessionCheck = null;
let lastSearch = null;
let archiveToRemove = null;

function node(tag, className, content = "") {
  const element = document.createElement(tag);
  if (className) element.className = className;
  element.textContent = content;
  return element;
}

function setStatus(id, message = "", error = false) {
  const target = byId(id);
  target.textContent = message;
  target.dataset.error = String(error);
}

function textValue(value) {
  return typeof value === "string" ? value.trim() : "";
}

function languageLabel(value) {
  const language = textValue(value);
  return Object.hasOwn(languageNames, language) ? languageNames[language] : "Idioma não informado";
}

function dateLabel(value) {
  const date = textValue(value);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return date;
  const parsed = new Date(date + "T00:00:00Z");
  return Number.isFinite(parsed.getTime()) ? dateFormat.format(parsed) : date;
}

function isAbort(error) {
  return error.name === "AbortError";
}

function expireSession(scope) {
  if (scope === session) showPairing("Sessão encerrada. Digite um novo código para conectar.", true);
}

async function request(path, { body, scope = session } = {}) {
  const post = body !== undefined;
  let response;
  try {
    response = await fetch(path, {
      method: post ? "POST" : "GET",
      headers: post ? { Accept: "application/json", "Content-Type": "application/json" } : { Accept: "application/json" },
      body: post ? JSON.stringify(body) : undefined,
      signal: scope?.signal,
      cache: "no-store",
      credentials: "same-origin"
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    throw new Error(networkError);
  }

  if (response.status === 401 && scope) {
    expireSession(scope);
    throw new DOMException("Sessão encerrada", "AbortError");
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(textValue(data?.error) || "A solicitação não foi concluída. Tente novamente.");
  }
  if (!data && response.status !== 204) {
    throw new Error("O aplicativo enviou uma resposta inválida.");
  }
  return data;
}

function showPanel(name, focus = false) {
  if (!panelFocus.has(name)) return;
  for (const destination of panelFocus.keys()) {
    byId(destination + "-panel").hidden = destination !== name;
  }
  for (const button of document.querySelectorAll(".nav-button")) {
    if (button.dataset.panel === name) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
  if (focus) panelFocus.get(name).focus();
}

function showPairing(message = "", focus = false) {
  session?.abort();
  session = null;
  lastSearch = null;
  archiveToRemove = null;
  catalogVersion++;
  articleVersion++;
  if (imageDialog.open) imageDialog.close();
  if (articleDialog.open) articleDialog.close();
  if (removeDialog.open) removeDialog.close();
  byId("catalog").replaceChildren();
  byId("source-list").replaceChildren();
  byId("answer-text").textContent = "";
  byId("article-text").textContent = "";
  byId("article-title").textContent = "Fonte";
  clearArticleImages();
  byId("remove-message").textContent = "";
  byId("search-notices").replaceChildren();
  byId("search-form").reset();
  byId("image-form").reset();
  clearImageResults();
  clearImagePreview();
  byId("results-section").hidden = true;
  searchSubmit.disabled = false;
  generateAnswer.disabled = false;
  removeConfirm.disabled = false;
  removeCancel.disabled = false;
  byId("refresh-catalog").disabled = false;
  byId("logout").disabled = false;
  setStatus("workspace-status");
  setStatus("search-status");
  workspace.hidden = true;
  pairScreen.hidden = false;
  pairCode.value = "";
  pairSubmit.disabled = false;
  setStatus("pair-status", message);
  if (focus) pairCode.focus();
}

function openWorkspace() {
  session?.abort();
  session = new AbortController();
  pairCode.value = "";
  pairScreen.hidden = true;
  workspace.hidden = false;
  showPanel("library", true);
  void refreshCatalog();
}

pairCode.addEventListener("input", () => {
  pairCode.value = pairCode.value.replace(/\D/g, "").slice(0, 8);
  setStatus("pair-status");
});

byId("pair-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (pairSubmit.disabled) return;
  if (!/^[0-9]{8}$/.test(pairCode.value)) {
    setStatus("pair-status", "Digite o código de oito dígitos.", true);
    pairCode.focus();
    return;
  }
  pairSubmit.disabled = true;
  setStatus("pair-status", "Conectando…");
  try {
    const data = await request("/api/pair", { body: { code: pairCode.value }, scope: null });
    if (data?.paired !== true) throw new Error("O aplicativo não confirmou o acesso.");
    openWorkspace();
  } catch (error) {
    setStatus("pair-status", error.message, true);
    pairCode.select();
  } finally {
    pairSubmit.disabled = false;
  }
});

for (const button of document.querySelectorAll("[data-panel]")) {
  button.addEventListener("click", () => showPanel(button.dataset.panel, true));
}

byId("logout").addEventListener("click", async (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  try {
    await request("/api/logout", { body: {} });
    showPairing("Sessão encerrada.", true);
  } catch (error) {
    if (!isAbort(error)) setStatus("workspace-status", error.message, true);
  } finally {
    button.disabled = false;
  }
});

function renderCatalog(libraries) {
  const catalog = byId("catalog");
  const count = libraries.length;
  byId("catalog-summary").textContent = count === 1 ? "1 acervo no catálogo" : count + " acervos no catálogo";
  if (!count) {
    catalog.replaceChildren(node("p", "empty", "Nenhum acervo no catálogo."));
    return;
  }
  const cards = libraries.map((archive) => {
    const title = textValue(archive.title) || textValue(archive.name) || "Acervo ZIM";
    const card = node("article", "archive");
    const heading = node("div", "archive-header");
    const icon = node("span", "archive-icon");
    icon.setAttribute("aria-hidden", "true");
    icon.append(node("span", "icon icon-library"));
    const titles = node("div");
    titles.append(node("h3", "", title));
    if (archive.name && archive.name !== title) titles.append(node("p", "archive-name", archive.name));
    heading.append(icon, titles);
    card.append(heading);

    const description = textValue(archive.description);
    if (description) card.append(node("p", "archive-description", description));
    const facts = [languageLabel(archive.language), textValue(archive.edition), dateLabel(archive.date)].filter(Boolean);
    const metadata = node("ul", "archive-meta");
    metadata.append(...facts.map((value) => node("li", "", value)));
    card.append(metadata);
    const footer = node("div", "archive-footer");
    const state = node("span", "archive-status", archive.available
      ? (archive.localCopy ? "Cópia no aplicativo" : "Arquivo disponível")
      : "Acervo indisponível");
    state.dataset.unavailable = String(!archive.available);
    footer.append(state);
    if (typeof archive.id === "string" && archive.id) {
      const remove = node("button", "button button-remove", "Remover");
      remove.type = "button";
      remove.setAttribute("aria-label", "Remover acervo " + title);
      remove.addEventListener("click", () => confirmRemoval(archive, title));
      footer.append(remove);
    }
    card.append(footer);
    return card;
  });
  catalog.replaceChildren(...cards);
}

async function refreshCatalog() {
  if (!session) return;
  const version = ++catalogVersion;
  const button = byId("refresh-catalog");
  const catalog = byId("catalog");
  const summary = byId("catalog-summary");
  button.disabled = true;
  catalog.setAttribute("aria-busy", "true");
  setStatus("catalog-status");
  try {
    const data = await request("/api/catalog");
    if (version === catalogVersion) renderCatalog(Array.isArray(data?.libraries) ? data.libraries : []);
  } catch (error) {
    if (version === catalogVersion && !isAbort(error)) {
      if (!catalog.hasChildNodes()) summary.textContent = "Catálogo indisponível";
      setStatus("catalog-status", error.message, true);
    }
  } finally {
    if (version === catalogVersion) {
      button.disabled = false;
      catalog.setAttribute("aria-busy", "false");
    }
  }
}
byId("refresh-catalog").addEventListener("click", refreshCatalog);

function confirmRemoval(archive, title) {
  archiveToRemove = archive;
  byId("remove-message").textContent = archive.localCopy
    ? "A cópia de “" + title + "” no aplicativo será apagada e o acervo sairá do catálogo. O arquivo original será preservado."
    : "“" + title + "” sairá do catálogo. O arquivo original será preservado.";
  setStatus("remove-status");
  removeDialog.showModal();
}

removeCancel.addEventListener("click", () => removeDialog.close());
removeDialog.addEventListener("close", () => {
  if (!removeDialog.open) archiveToRemove = null;
});
removeDialog.addEventListener("cancel", (event) => {
  if (removeConfirm.disabled) event.preventDefault();
});
byId("remove-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!archiveToRemove || removeConfirm.disabled) return;
  removeConfirm.disabled = true;
  removeCancel.disabled = true;
  setStatus("remove-status", "Removendo acervo…");
  try {
    const result = await request("/api/libraries/remove", { body: { id: archiveToRemove.id } });
    removeDialog.close();
    byId("catalog-heading").setAttribute("tabindex", "-1");
    byId("catalog-heading").focus();
    lastSearch = null;
    byId("results-section").hidden = true;
    byId("source-list").replaceChildren();
    setStatus("search-status");
    clearImageResults();
    setStatus("workspace-status", result?.notice || "Acervo removido.");
    await refreshCatalog();
  } catch (error) {
    if (!isAbort(error)) setStatus("remove-status", error.message, true);
  } finally {
    removeConfirm.disabled = false;
    removeCancel.disabled = false;
  }
});

function renderResults(data) {
  const sources = Array.isArray(data?.sources) ? data.sources : [];
  const length = sources.length;
  const answer = textValue(data?.answer);
  const notices = Array.isArray(data?.notices) ? data.notices.filter((notice) => textValue(notice)) : [];
  const sourceList = byId("source-list");
  byId("results-section").hidden = false;
  byId("results-summary").textContent = length === 1 ? "1 fonte encontrada" : length + " fontes encontradas";
  generateAnswer.hidden = Boolean(answer) || !lastSearch;
  generateAnswer.textContent = length ? "Gerar resposta com IA" : "Buscar com IA";
  byId("answer-panel").hidden = !answer;
  byId("answer-text").textContent = answer;
  byId("search-notices").hidden = notices.length === 0;
  byId("search-notices").replaceChildren(...notices.map((notice) => node("p", "", notice)));

  if (!length) {
    sourceList.replaceChildren(node("li", "empty", "Nenhum trecho encontrado. Tente outro termo ou confira os acervos disponíveis."));
    return;
  }
  sourceList.replaceChildren(...sources.map((source, index) => {
    const item = node("li", "source");
    const title = textValue(source.title) || "Artigo";
    const origin = [textValue(source.book), languageLabel(source.language)].filter(Boolean).join(" · ");
    item.append(node("h3", "", "[" + (index + 1) + "] " + title), node("p", "source-meta", origin));
    if (source.excerpt) item.append(node("p", "source-excerpt multiline", source.excerpt));
    if (hasArchiveResource(source)) {
      const open = node("button", "button button-secondary", "Abrir fonte");
      open.type = "button";
      open.setAttribute("aria-label", "Abrir fonte " + title);
      open.addEventListener("click", () => openArticle(source, title));
      item.append(open);
    }
    return item;
  }));
}

byId("search-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = searchQuery.value.trim();
  if (!query || searchSubmit.disabled) return;
  const options = { query, includeEnglish: byId("include-english").checked };
  lastSearch = null;
  byId("results-section").hidden = true;
  searchSubmit.disabled = true;
  setStatus("search-status", "Pesquisando nos acervos…");
  try {
    const data = await request("/api/search", { body: options });
    lastSearch = options;
    renderResults(data);
    setStatus("search-status");
  } catch (error) {
    if (!isAbort(error)) setStatus("search-status", error.message, true);
  } finally {
    searchSubmit.disabled = false;
  }
});

generateAnswer.addEventListener("click", async () => {
  if (!lastSearch || generateAnswer.disabled) return;
  generateAnswer.disabled = true;
  searchSubmit.disabled = true;
  const hadSources = Boolean(byId("source-list").querySelector(".source"));
  setStatus("search-status", hadSources ? "Gerando resposta no Android…" : "Tentando outra busca com a IA local…");
  try {
    const result = await request("/api/answer", { body: lastSearch });
    const focusResult = !byId("search-panel").hidden && [document.body, generateAnswer].includes(document.activeElement);
    renderResults(result);
    const hasAnswer = Boolean(textValue(result?.answer));
    const found = Array.isArray(result?.sources) && result.sources.length > 0;
    setStatus("search-status", hasAnswer
      ? ""
      : (found ? "A IA local não gerou resposta para esta consulta." : "A busca com IA não encontrou novas fontes."));
    if (hasAnswer && focusResult) byId("answer-title").focus();
  } catch (error) {
    if (!isAbort(error)) setStatus("search-status", error.message, true);
  } finally {
    generateAnswer.disabled = false;
    searchSubmit.disabled = false;
  }
});

function hasArchiveResource(value) {
  return typeof value?.libraryId === "string" && Boolean(value.libraryId)
    && typeof value.path === "string" && Boolean(value.path);
}

function archiveUrl(endpoint, { libraryId, path }) {
  return endpoint + "?" + new URLSearchParams({ libraryId, path });
}

function checkImageSession(scope) {
  if (!scope || scope !== session) return Promise.resolve(false);
  if (imageSessionCheck?.scope === scope) return imageSessionCheck.result;
  const result = request("/api/session", { scope }).then((data) => {
    if (data?.paired === false) expireSession(scope);
    return data?.paired === true && scope === session;
  }).catch(() => false).finally(() => {
    if (imageSessionCheck?.result === result) imageSessionCheck = null;
  });
  imageSessionCheck = { scope, result };
  return result;
}

function clearImageResults() {
  imageSearchVersion++;
  byId("image-results").hidden = true;
  byId("image-list").replaceChildren();
  byId("image-notices").replaceChildren();
  imageSubmit.removeAttribute("aria-disabled");
  setStatus("image-status");
}

function imageArticle(picture) {
  const source = { libraryId: picture.libraryId, path: picture.articlePath };
  return hasArchiveResource(source) ? source : null;
}

function imageOrigin(picture, title) {
  return [textValue(picture.articleTitle), textValue(picture.book)]
    .filter((value) => value && value !== title).join(" · ");
}

function pictureTitle(picture) {
  return textValue(picture.title) || textValue(picture.articleTitle) || "Imagem do acervo";
}

function pictureThumbnail(picture, title, scope) {
  const frame = node("div", "image-frame");
  const image = node("img");
  image.alt = title;
  image.loading = "lazy";
  image.decoding = "async";
  image.addEventListener("error", () => {
    if (!frame.isConnected) return;
    frame.replaceChildren(node("p", "image-unavailable", "Imagem indisponível"));
    void checkImageSession(scope);
  }, { once: true });
  image.src = archiveUrl("/api/image", picture);
  frame.append(image);
  return frame;
}

function renderImages(data) {
  const pictures = Array.isArray(data?.images) ? data.images.filter(hasArchiveResource) : [];
  const notices = Array.isArray(data?.notices) ? data.notices.filter((notice) => textValue(notice)) : [];
  const scope = session;
  byId("image-results").hidden = !pictures.length && !notices.length;
  byId("image-notices").hidden = notices.length === 0;
  byId("image-notices").replaceChildren(...notices.map((notice) => node("p", "", notice)));
  setStatus("image-status", pictures.length === 1 ? "1 imagem encontrada."
    : pictures.length ? pictures.length + " imagens encontradas." : "Nenhuma imagem encontrada.");

  byId("image-list").replaceChildren(...pictures.map((picture) => {
    const title = pictureTitle(picture);
    const card = node("li", "image-card");

    const details = node("div", "image-details");
    details.append(node("h2", "", title));
    const origin = imageOrigin(picture, title);
    if (origin) details.append(node("p", "source-meta", origin));
    const actions = node("div", "image-actions");
    const preview = node("button", "button button-secondary", "Ampliar");
    preview.type = "button";
    preview.setAttribute("aria-label", "Ampliar imagem " + title);
    preview.addEventListener("click", () => openImagePreview(picture, title));
    actions.append(preview);
    const source = imageArticle(picture);
    if (source) {
      const articleTitle = textValue(picture.articleTitle) || "Artigo de origem";
      const article = node("button", "button button-secondary", "Abrir artigo");
      article.type = "button";
      article.setAttribute("aria-label", "Abrir artigo de origem de " + title);
      article.addEventListener("click", () => openArticle(source, articleTitle));
      actions.append(article);
    }
    details.append(actions);
    card.append(pictureThumbnail(picture, title, scope), details);
    return card;
  }));
}

byId("image-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!session || imageSubmit.getAttribute("aria-disabled") === "true") return;
  const query = imageQuery.value.trim();
  if (!query) {
    setStatus("image-status", "Digite um assunto para buscar imagens.", true);
    imageQuery.focus();
    return;
  }
  clearImageResults();
  const version = imageSearchVersion;
  imageSubmit.setAttribute("aria-disabled", "true");
  setStatus("image-status", "Buscando imagens nos acervos…");
  try {
    const data = await request("/api/images/search", {
      body: { query, includeEnglish: byId("image-include-english").checked }
    });
    if (version === imageSearchVersion) renderImages(data);
  } catch (error) {
    if (version === imageSearchVersion && !isAbort(error)) setStatus("image-status", error.message, true);
  } finally {
    if (version === imageSearchVersion) imageSubmit.removeAttribute("aria-disabled");
  }
});

function clearImagePreview() {
  imagePreview.hidden = true;
  imagePreview.replaceChildren();
  imagePreviewArticle.hidden = true;
  imagePreviewArticle.onclick = null;
  byId("image-preview-origin").textContent = "";
  byId("image-dialog-title").textContent = "Imagem";
  setStatus("image-dialog-status");
}

imageDialog.addEventListener("close", () => {
  if (!imageDialog.open) clearImagePreview();
});

async function openImagePreview(picture, title) {
  clearImagePreview();
  const scope = session;
  const image = node("img");
  image.alt = title;
  image.decoding = "async";
  byId("image-dialog-title").textContent = title;
  byId("image-preview-origin").textContent = imageOrigin(picture, title);
  const source = imageArticle(picture);
  if (source) {
    imagePreviewArticle.hidden = false;
    imagePreviewArticle.onclick = () => {
      imageDialog.close();
      void openArticle(source, textValue(picture.articleTitle) || "Artigo de origem");
    };
  }
  setStatus("image-dialog-status", "Carregando imagem…");
  image.addEventListener("load", () => {
    if (!imageDialog.open || !image.isConnected) return;
    imagePreview.hidden = false;
    setStatus("image-dialog-status");
  }, { once: true });
  image.addEventListener("error", () => {
    if (!imageDialog.open || !image.isConnected) return;
    setStatus("image-dialog-status", "Não foi possível carregar a imagem deste acervo.", true);
    void checkImageSession(scope);
  }, { once: true });
  imagePreview.replaceChildren(image);
  imageDialog.showModal();
  const paired = await checkImageSession(scope);
  if (!imageDialog.open || !image.isConnected) return;
  if (paired) image.src = archiveUrl("/api/image", picture);
  else setStatus("image-dialog-status", "Não foi possível verificar a sessão no Android.", true);
}

articleDialog.addEventListener("close", () => {
  if (articleDialog.open) return;
  articleVersion++;
  clearArticleImages();
});

byId("article-image-jump").addEventListener("click", () => byId("article-images-title").focus());

function clearArticleImages() {
  byId("article-images").hidden = true;
  byId("article-image-jump").hidden = true;
  byId("article-image-list").replaceChildren();
}

function renderArticleImages(pictures, hasText, scope) {
  byId("article-images").hidden = pictures.length === 0;
  byId("article-image-jump").hidden = pictures.length === 0 || !hasText;
  byId("article-image-list").replaceChildren(...pictures.map((picture) => {
    const title = pictureTitle(picture);
    const figure = node("figure", "article-image");
    figure.append(pictureThumbnail(picture, title, scope), node("figcaption", "", title));
    return figure;
  }));
}

async function openArticle(source, title) {
  const version = ++articleVersion;
  const scope = session;
  byId("article-title").textContent = title;
  byId("article-text").textContent = "";
  clearArticleImages();
  setStatus("article-status", "Abrindo a fonte…");
  articleDialog.showModal();
  try {
    const data = await request(archiveUrl("/api/article", source));
    if (version !== articleVersion || !articleDialog.open) return;
    byId("article-title").textContent = textValue(data?.title) || title;
    const article = textValue(data?.text);
    const pictures = Array.isArray(data?.images)
      ? data.images.filter((picture) => hasArchiveResource(picture) && picture.libraryId === source.libraryId)
      : [];
    byId("article-text").textContent = article;
    renderArticleImages(pictures, Boolean(article), scope);
    if (!article) setStatus("article-status", pictures.length
      ? "Sem texto disponível. Veja as imagens do artigo abaixo."
      : "O artigo não tem texto disponível.");
    else setStatus("article-status", data?.shortened === true ? "Exibindo o início do artigo." : "");
  } catch (error) {
    if (version === articleVersion && !isAbort(error)) setStatus("article-status", error.message, true);
  }
}

async function start() {
  try {
    const data = await request("/api/session", { scope: null });
    if (data?.paired === true) openWorkspace();
    else showPairing();
  } catch (error) {
    showPairing();
    setStatus("pair-status", error.message, true);
  }
}

void start();
