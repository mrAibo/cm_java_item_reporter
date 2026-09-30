/*
 * CM Insight console - the complete offline operator UI.
 *
 * Hard rules this file follows, because the goal's security tests assert them:
 *   1. every dynamic value is written with textContent or created as an element attribute that is NOT
 *      HTML - never innerHTML, never insertAdjacentHTML and never a data: URL. ItemType names,
 *      classifications and retention policy names come from IBM CM and are attacker-influenced data;
 *   2. no request leaves this origin: no CDN, no external font, no analytics script, no fetch to any other
 *      host. Every URL below is a relative path served by this application;
 *   3. a state-changing request carries its own exact action header and is only sent from an explicit user
 *      action - never from a page load, and never as a side effect of rendering;
 *   4. a covered subtotal is never presented as a complete total: each total is rendered with the coverage
 *      the payload states, and an incomplete snapshot says so in words.
 *
 * The console is a single page with hash routing, so a view can be linked to and the browser's back button
 * works, without any client-side framework or build step.
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------ constants */

  var ACTION_HEADER = "X-CM-Insight-Action";
  var ACTION_REPOSITORY_SELECT = "repository-select";
  var ACTION_STATISTICS_REFRESH = "statistics-refresh";
  var ACTION_ITEM_REFRESH = "statistics-item-refresh";
  var ACTION_REPORT_GENERATE = "report-generate";

  var REPORTS_PATH = "/api/reports";
  var ITEM_REFRESH_PREFIX = "/api/statistics/item/";

  var VIEWS = ["repository", "dashboard", "itemtypes", "retention", "history", "reports", "system"];

  var HISTORY_PAGE_SIZE = 25;
  var REPORT_LIST_LIMIT = 25;
  var SCAN_POLL_MILLIS = 2000;
  var MAX_MESSAGE = 300;

  /* ------------------------------------------------------------------ state */

  var state = {
    view: "dashboard",
    loading: false,
    statistics: null,
    statisticsError: "",
    repositories: null,
    itemTypes: [],
    itemTypesError: "",
    retention: null,
    retentionError: "",
    history: { page: null, cursorStack: [], error: "", detail: null, detailId: "" },
    reports: { list: null, error: "", lastGenerated: null },
    itemTypeFilter: "",
    itemTypeSort: "name",
    itemTypeAscending: true,
    selectedItemType: null,
    scanTimer: null
  };

  /* ------------------------------------------------------------------ DOM helpers */

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    if (text !== undefined && text !== null) {
      node.textContent = String(text);
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function add(parent, child) {
    if (child) {
      parent.appendChild(child);
    }
    return parent;
  }

  function card(label, value, sub, valueClass) {
    var box = el("div", "card");
    add(box, el("div", "label", label));
    add(box, el("div", valueClass ? "value " + valueClass : "value", value));
    if (sub) {
      add(box, el("div", "sub", sub));
    }
    return box;
  }

  function definitionList(pairs) {
    var list = el("dl", "kv");
    pairs.forEach(function (pair) {
      add(list, el("dt", null, pair[0]));
      var value = el("dd");
      if (pair[1] instanceof Node) {
        add(value, pair[1]);
      } else {
        value.textContent = pair[1] === undefined || pair[1] === null ? "" : String(pair[1]);
      }
      add(list, value);
    });
    return list;
  }

  function badge(text, kind) {
    return el("span", "badge-" + (kind || "muted"), text);
  }

  function banner(kind, title, lines) {
    var box = el("div", "banner " + kind);
    add(box, el("div", "banner-title", title));
    if (lines && lines.length) {
      var list = el("ul");
      lines.forEach(function (line) {
        add(list, el("li", null, line));
      });
      add(box, list);
    }
    return box;
  }

  function note(text, strong) {
    return el("p", strong ? "note strong" : "note", text);
  }

  function section(title, subtitle) {
    var panel = el("section", "panel");
    var head = el("div", "panel-head");
    var left = el("div");
    add(left, el("h2", null, title));
    if (subtitle) {
      add(left, note(subtitle));
    }
    add(head, left);
    add(panel, head);
    return panel;
  }

  /* ------------------------------------------------------------------ formatting */

  function fmtNumber(value) {
    if (value === null || value === undefined) {
      return "";
    }
    var text = String(value);
    return text.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }

  function fmtDuration(millis) {
    if (millis === null || millis === undefined) {
      return "unknown";
    }
    var total = Math.max(0, Math.round(Number(millis) / 1000));
    if (total < 60) {
      return total + " s";
    }
    var minutes = Math.floor(total / 60);
    var seconds = total % 60;
    if (minutes < 60) {
      return minutes + " m " + seconds + " s";
    }
    var hours = Math.floor(minutes / 60);
    return hours + " h " + (minutes % 60) + " m";
  }

  function fmtAge(millis) {
    if (millis === null || millis === undefined) {
      return "never";
    }
    var seconds = Math.max(0, Math.round(Number(millis) / 1000));
    if (seconds < 90) {
      return seconds + " s ago";
    }
    var minutes = Math.round(seconds / 60);
    if (minutes < 90) {
      return minutes + " m ago";
    }
    var hours = Math.round(minutes / 60);
    if (hours < 48) {
      return hours + " h ago";
    }
    return Math.round(hours / 24) + " d ago";
  }

  function fmtBytes(bytes) {
    if (bytes === null || bytes === undefined) {
      return "unknown";
    }
    var value = Number(bytes);
    if (value < 1024) {
      return value + " B";
    }
    if (value < 1024 * 1024) {
      return (value / 1024).toFixed(1) + " KiB";
    }
    return (value / (1024 * 1024)).toFixed(1) + " MiB";
  }

  function fmtInstant(iso) {
    if (!iso) {
      return "unknown";
    }
    var parsed = new Date(iso);
    if (isNaN(parsed.getTime())) {
      return String(iso);
    }
    return parsed.toLocaleString();
  }

  function safeMessage(text) {
    if (!text) {
      return "";
    }
    var value = String(text);
    return value.length > MAX_MESSAGE ? value.slice(0, MAX_MESSAGE) + "..." : value;
  }

  function errorText(result) {
    if (result && result.body && result.body.error) {
      var code = result.body.error.code ? String(result.body.error.code) : "error";
      var message = result.body.error.message ? safeMessage(result.body.error.message) : "";
      return code + (message ? ": " + message : "");
    }
    if (result && result.body && result.body.reason) {
      return safeMessage(result.body.reason);
    }
    return "HTTP " + (result ? result.status : "?");
  }

  /* ------------------------------------------------------------------ metric rendering */

  function metricText(metric) {
    if (!metric) {
      return "UNAVAILABLE";
    }
    if (metric.available === true) {
      return fmtNumber(metric.value);
    }
    return metric.state === "ERROR" ? "ERROR" : "UNAVAILABLE";
  }

  function metricNode(metric) {
    if (metric && metric.available === true) {
      var strong = el("span", null, fmtNumber(metric.value));
      return strong;
    }
    var state = metric && metric.state ? metric.state : "UNAVAILABLE";
    var node = badge(state === "ERROR" ? "ERROR" : "UNAVAILABLE", state === "ERROR" ? "bad" : "muted");
    if (metric && metric.reason) {
      node.title = safeMessage(metric.reason);
    }
    return node;
  }

  function freshnessBadge(freshness) {
    if (!freshness || freshness.known !== true) {
      return badge("NO COMPLETED SCAN", "muted");
    }
    var label = (freshness.stale ? "STALE" : "FRESH") + " (threshold "
      + fmtNumber(freshness.thresholdSeconds) + " s)";
    return badge(label, freshness.stale ? "warn" : "ok");
  }

  function analyticsBadge(code) {
    var kind = code === "AVAILABLE" ? "ok" : code === "DISABLED" ? "muted" : "warn";
    return badge(code || "UNKNOWN", kind);
  }

  /* ------------------------------------------------------------------ API access */

  function api(path, options) {
    var init = options || {};
    init.credentials = "same-origin";
    init.cache = "no-store";
    var headers = init.headers || {};
    headers["Accept"] = "application/json";
    init.headers = headers;
    return fetch(path, init).then(function (response) {
      return response.text().then(function (text) {
        var body = null;
        if (text) {
          try {
            body = JSON.parse(text);
          } catch (ignored) {
            body = null;
          }
        }
        return { status: response.status, ok: response.ok, body: body };
      });
    });
  }

  function post(path, action, query) {
    var headers = {};
    if (action) {
      headers[ACTION_HEADER] = action;
    }
    return api(path + (query ? "?" + query : ""), { method: "POST", headers: headers });
  }

  function encode(value) {
    return encodeURIComponent(String(value));
  }

  /* ------------------------------------------------------------------ chrome */

  var viewHost = document.getElementById("view");
  var drawer = document.getElementById("drawer");
  var drawerBackdrop = document.getElementById("drawer-backdrop");
  var drawerTitle = document.getElementById("drawer-title");
  var drawerBody = document.getElementById("drawer-body");
  var toast = document.getElementById("toast");
  var factRepository = document.getElementById("fact-repository");
  var factAnalytics = document.getElementById("fact-analytics");
  var factFreshness = document.getElementById("fact-freshness");

  var toastTimer = null;
  var focusBeforeDrawer = null;
  /** The ItemTypes table panel, replaced in place so the search field keeps focus while typing. */
  var itemTypeTableHost = null;

  function showToast(text, kind) {
    toast.textContent = text;
    toast.className = "toast " + (kind || "");
    toast.hidden = false;
    if (toastTimer) {
      window.clearTimeout(toastTimer);
    }
    toastTimer = window.setTimeout(function () {
      toast.hidden = true;
    }, 6000);
  }

  function openDrawer(title, build) {
    focusBeforeDrawer = document.activeElement;
    drawerTitle.textContent = title;
    clear(drawerBody);
    build(drawerBody);
    drawer.hidden = false;
    drawerBackdrop.hidden = false;
    drawer.setAttribute("aria-hidden", "false");
    document.getElementById("drawer-close").focus();
  }

  function closeDrawer() {
    drawer.hidden = true;
    drawerBackdrop.hidden = true;
    drawer.setAttribute("aria-hidden", "true");
    clear(drawerBody);
    if (focusBeforeDrawer && focusBeforeDrawer.focus) {
      focusBeforeDrawer.focus();
    }
  }

  function setActiveNav(view) {
    document.querySelectorAll(".nav-button").forEach(function (button) {
      if (button.getAttribute("data-view") === view) {
        button.setAttribute("aria-current", "page");
      } else {
        button.removeAttribute("aria-current");
      }
    });
  }

  function updateTopFacts() {
    var stats = state.statistics;
    var repositoryId = stats && stats.repositoryId ? stats.repositoryId : "";
    if (!repositoryId && state.repositories && state.repositories.activeRepositoryId) {
      repositoryId = state.repositories.activeRepositoryId;
    }
    factRepository.textContent = "Repository: " + (repositoryId || "none active");

    var stateCode = stats && stats.state ? stats.state : "UNKNOWN";
    factAnalytics.textContent = "Analytics: " + stateCode;

    if (stats && stats.snapshot && stats.snapshot.present) {
      var fresh = stats.freshness || {};
      factFreshness.textContent = "Statistics: " + (fresh.stale ? "stale" : "fresh")
        + ", captured " + fmtAge(stats.ageMillis);
    } else {
      factFreshness.textContent = "Statistics: no completed scan";
    }
  }

  /* ------------------------------------------------------------------ reloading */

  function loadRepositories() {
    return api("/api/repositories").then(function (result) {
      if (result.status === 200 && result.body) {
        state.repositories = result.body;
        state.repositories.error = "";
      } else {
        state.repositories = state.repositories || { repositories: [] };
        state.repositories.error = errorText(result);
      }
      updateTopFacts();
      return result;
    });
  }

  function loadStatistics() {
    return api("/api/statistics").then(function (result) {
      if (result.status === 200 && result.body) {
        state.statistics = result.body;
        state.statisticsError = "";
      } else {
        state.statistics = null;
        state.statisticsError = errorText(result);
      }
      updateTopFacts();
      return result;
    });
  }

  function loadItemTypes() {
    return api("/api/itemtypes").then(function (result) {
      if (result.status === 200 && result.body && result.body.itemTypes) {
        state.itemTypes = result.body.itemTypes;
        state.itemTypesError = "";
      } else {
        state.itemTypes = [];
        state.itemTypesError = errorText(result);
      }
      return result;
    });
  }

  function loadRetention() {
    return api("/api/retention/policies").then(function (result) {
      if (result.status === 200 && result.body && result.body.policies) {
        state.retention = result.body.policies;
        state.retentionError = "";
      } else {
        state.retention = [];
        state.retentionError = errorText(result);
      }
      return result;
    });
  }

  function loadHistory(cursor) {
    var query = "?" + "limit=" + HISTORY_PAGE_SIZE;
    if (cursor) {
      query += "&before=" + encode(cursor);
    }
    if (state.repositories && state.repositories.activeRepositoryId) {
      query += "&repository=" + encode(state.repositories.activeRepositoryId);
    }
    return api("/api/history" + query).then(function (result) {
      if (result.status === 200 && result.body) {
        state.history.page = result.body;
        state.history.error = "";
      } else {
        state.history.error = errorText(result);
        state.history.page = null;
      }
      return result;
    });
  }

  function loadReports() {
    return api(REPORTS_PATH + "?limit=" + REPORT_LIST_LIMIT).then(function (result) {
      if (result.status === 200 && result.body) {
        state.reports.list = result.body;
        state.reports.error = "";
      } else {
        state.reports.list = null;
        state.reports.error = errorText(result);
      }
      return result;
    });
  }

  function refreshChrome() {
    return Promise.all([loadRepositories(), loadStatistics()]).then(function () {
      updateTopFacts();
    });
  }

  /* ------------------------------------------------------------------ polling */

  function stopScanPolling() {
    if (state.scanTimer) {
      window.clearTimeout(state.scanTimer);
      state.scanTimer = null;
    }
  }

  function pollScan() {
    stopScanPolling();
    var scan = state.statistics && state.statistics.scan;
    if (!scan || !scan.running) {
      return;
    }
    state.scanTimer = window.setTimeout(function () {
      loadStatistics().then(function () {
        if (state.view === "dashboard") {
          render();
        }
        pollScan();
      });
    }, SCAN_POLL_MILLIS);
  }

  /* ------------------------------------------------------------------ views: repository */

  function viewRepository() {
    var host = el("div");
    add(host, pageHead("Repository", "Choose the one active repository. Selecting a repository is a local "
      + "state change and requires the repository-select action header, which this console sends only when "
      + "you press the button."));

    var status = state.repositories;
    if (status && status.error) {
      add(host, banner("bad", "The repository list could not be read", [status.error]));
    }
    if (!status) {
      add(host, el("p", "loading", "Loading repositories..."));
      return host;
    }

    var adapter = status.adapter || {};
    var adapterLines = [
      "Adapter availability: " + (adapter.availability || "UNKNOWN")
        + (adapter.available ? " (usable)" : " (a repository can be listed but not activated)"),
      "Provider: " + (adapter.providerId || "none installed"),
      "Adapter version: " + (adapter.adapterVersion || "unknown"),
      "IBM CM API release: " + (adapter.sdkRelease || "unknown")
    ];
    add(host, banner(adapter.available ? "ok" : "warn",
      adapter.available ? "A CM read adapter is installed" : "No usable CM adapter is installed", adapterLines));

    var panel = section("Configured repositories",
      "Credentials are never shown: only the id, display name, SSID and database family are published.");
    var grid = el("div", "grid");
    (status.repositories || []).forEach(function (repository) {
      var box = el("div", "card");
      add(box, el("div", "value", repository.name || repository.id));
      add(box, el("div", "sub", "id " + repository.id + " - SSID " + repository.ssid
        + " - " + repository.vendor));
      var actions = el("div", "panel-actions spaced-top");
      if (repository.active) {
        add(actions, badge("ACTIVE", "ok"));
      } else {
        var button = el("button", "button primary", "Select this repository");
        button.type = "button";
        button.addEventListener("click", function () {
          selectRepository(repository.id, button);
        });
        add(actions, button);
      }
      add(box, actions);
      add(grid, box);
    });
    add(panel, grid);
    add(host, panel);

    var statusPanel = section("Lifecycle and refusal state");
    loadRepositoryStatusInto(statusPanel);
    add(host, statusPanel);
    return host;
  }

  function loadRepositoryStatusInto(panel) {
    add(panel, el("p", "loading", "Reading lifecycle state..."));
    api("/api/repositories/status").then(function (result) {
      clear(panel);
      var head = el("div", "panel-head");
      add(head, el("h2", null, "Lifecycle and refusal state"));
      add(panel, head);
      if (result.status !== 200 || !result.body) {
        add(panel, banner("bad", "Lifecycle state could not be read", [errorText(result)]));
        return;
      }
      var body = result.body;
      add(panel, definitionList([
        ["State", body.state + " - " + (body.stateDescription || "")],
        ["Usable", body.usable ? "yes" : "no"],
        ["Active repository", body.repositoryId || "none"],
        ["Context close state", body.contextCloseState || "not available"],
        ["Close failures", fmtNumber(body.closeFailureCount)],
        ["Unproven close reports", fmtNumber(body.uncertainCloseReportCount)],
        ["Recorded refusal", body.refusal || "none"],
        ["Last failure recorded", body.lastFailureRecorded ? "yes (text is never published)" : "no"],
        ["Retained context", body.retainedContext || "none"],
        ["Retained close state", body.retainedCloseState || "not available"]
      ]));
    });
  }

  function selectRepository(id, button) {
    button.disabled = true;
    post("/api/repositories/select", ACTION_REPOSITORY_SELECT, "repository=" + encode(id))
      .then(function (result) {
        button.disabled = false;
        if (result.status === 200) {
          showToast("Repository " + id + " is now active.", "ok");
          state.history.page = null;
          state.history.cursorStack = [];
          state.history.detail = null;
          state.selectedItemType = null;
          refreshAll();
        } else {
          showToast("Repository was not selected: " + errorText(result), "bad");
        }
      });
  }

  /* ------------------------------------------------------------------ views: dashboard */

  function viewDashboard() {
    var host = el("div");
    var head = pageHead("Dashboard", "One source of truth: the latest COMPLETED full scan. A stale snapshot "
      + "stays visible, and no page load starts a scan.");
    var actions = el("div", "panel-actions");
    var refresh = el("button", "button primary", "Start full scan");
    refresh.type = "button";
    var running = !!(state.statistics && state.statistics.scan && state.statistics.scan.running);
    refresh.disabled = running;
    refresh.addEventListener("click", function () {
      startFullScan(refresh);
    });
    add(actions, refresh);
    add(head, actions);
    add(host, head);

    if (state.statisticsError) {
      add(host, banner("bad", "Statistics could not be read", [state.statisticsError]));
    }
    var stats = state.statistics;
    if (!stats) {
      add(host, el("p", "loading", "Reading statistics..."));
      return host;
    }

    if (stats.available !== true) {
      add(host, banner("warn", "Analytics is not available (" + (stats.state || "UNKNOWN") + ")",
        [safeMessage(stats.reason) || "No reason was reported.",
          "Repository activation, the ItemType reads and the retention viewer keep working."]));
    }

    var snapshot = stats.snapshot || {};
    var scan = stats.scan || {};
    var freshness = stats.freshness || {};

    var cards = el("div", "grid");
    add(cards, card("Active repository", stats.repositoryId || "none active",
      stats.repositoryActive ? "the repository context is usable" : "no repository context is active"));
    add(cards, card("Latest full scan", snapshot.present ? fmtInstant(snapshot.capturedAt) : "no completed scan",
      snapshot.present ? "captured " + fmtAge(stats.ageMillis) : "run a full scan to publish the first snapshot"));
    var freshBox = card("Freshness", freshness.state || "UNKNOWN",
      freshness.known === true
        ? "age " + fmtAge(freshness.ageMillis) + ", threshold " + fmtNumber(freshness.thresholdSeconds)
          + " s (cache.statistics.ttl.seconds)"
        : "nothing has been captured yet");
    add(cards, freshBox);
    add(cards, card("Scan phase", (scan.running ? "RUNNING - " : "") + (scan.phase || "IDLE"),
      scan.running
        ? "started " + fmtInstant(scan.startedAt) + ", " + fmtNumber(scan.completed) + " of "
          + fmtNumber(scan.total) + " ItemTypes"
        : "last scan finished " + (scan.finishedAt ? fmtInstant(scan.finishedAt) : "never")));
    add(cards, card("Scan progress", fmtNumber(scan.completed) + " / " + fmtNumber(scan.total),
      "failed " + fmtNumber(scan.failed) + ", rate "
        + (scan.ratePerSecond ? Number(scan.ratePerSecond).toFixed(2) : "0") + " ItemTypes/s"));
    add(cards, card("Scan duration", scan.durationMillis ? fmtDuration(scan.durationMillis) : "not running",
      scan.currentItemType ? "currently measuring " + scan.currentItemType : "no ItemType in flight"));
    add(host, cards);

    if (scan.running && snapshot.present) {
      add(host, note("A scan is running. The totals below are the PREVIOUS completed snapshot and stay "
        + "visible until the new one is published.", true));
    }

    add(host, totalsPanel(snapshot, freshness));
    add(host, classificationPanel(snapshot));
    return host;
  }

  function totalValueNode(metric, complete) {
    var wrap = el("span");
    add(wrap, metricNode(metric));
    if (metric && metric.available === true && !complete) {
      add(wrap, el("span", "hint", " (covered subtotal)"));
    }
    return wrap;
  }

  function totalsPanel(snapshot, freshness) {
    var panel = section("Logical totals and coverage",
      "Every total is published with the coverage it was computed from. A covered subtotal is labelled as such "
      + "and is never presented as a complete repository total.");
    if (!snapshot || !snapshot.present) {
      add(panel, banner("warn", "No completed full scan yet",
        ["The dashboard has no totals to show. Use \u0022Start full scan\u0022 above, or check the analytics "
          + "state and GET /api/diagnostics/jdbc."]));
      return panel;
    }
    var totals = snapshot.totals || {};
    var coverage = snapshot.coverage || {};
    var complete = coverage.complete === true;

    var coverageLines = [
      "ItemTypes in the frozen list: " + fmtNumber(coverage.itemTypes),
      "ItemTypes that contributed a measured total: " + fmtNumber(coverage.itemTypesWithTotals),
      "ItemTypes that failed: " + fmtNumber(coverage.failedItemTypes),
      complete ? "Coverage: COMPLETE - every frozen ItemType was measured completely."
        : "Coverage: INCOMPLETE - this snapshot does not cover the whole repository."
    ];
    add(panel, banner(complete ? "ok" : "warn",
      complete ? "This snapshot covers the whole frozen ItemType list" : "This snapshot is partial", coverageLines));

    var grid = el("div", "grid");
    add(grid, card("Logical items (distinct ItemID)",
      metricText(totals.totalItems),
      complete ? "complete total over all " + fmtNumber(coverage.itemTypes) + " ItemTypes"
        : "covered subtotal: measured over " + fmtNumber(coverage.itemTypesWithTotals) + " of "
          + fmtNumber(coverage.itemTypes) + " ItemTypes - NOT a complete total"));
    add(grid, card("ItemTypes with totals", fmtNumber(totals.itemTypesWithTotals) + " / " + fmtNumber(totals.itemTypes),
      "failed: " + fmtNumber(totals.failedItemTypes)));
    add(grid, card("Versions", metricText(totals.versions), "UNAVAILABLE in this goal: no number is computed"));
    add(grid, card("Parts", metricText(totals.parts), "UNAVAILABLE in this goal: no number is computed"));
    add(grid, card("Snapshot captured", fmtInstant(snapshot.capturedAt),
      freshness && freshness.known ? "captured " + fmtAge(freshness.ageMillis) : "age unknown"));
    add(panel, grid);

    if (snapshot.partialFailureCount > 0) {
      var failed = (snapshot.itemTypes || []).filter(function (row) {
        return row.status === "ERROR" || row.status === "PARTIAL";
      });
      var lines = failed.slice(0, 20).map(function (row) {
        return row.name + " (ItemType id " + row.itemTypeId + "): " + row.status
          + (row.error ? " - " + safeMessage(row.error) : "");
      });
      if (failed.length > 20) {
        lines.push("... and " + (failed.length - 20) + " more; see the ItemTypes view.");
      }
      add(panel, banner("warn", "Partial or failed ItemTypes in this snapshot (" + failed.length + ")", lines));
    }
    return panel;
  }

  function classificationPanel(snapshot) {
    var panel = section("Classification totals",
      "Subtotals grouped by the classification already present in metadata. Each row states the failure count "
      + "it was computed with.");
    if (!snapshot || !snapshot.present) {
      add(panel, note("No snapshot is available yet."));
      return panel;
    }
    var totals = snapshot.totals || {};
    var groups = totals.byClassification || [];
    if (!groups.length) {
      add(panel, note("This snapshot reports no classification groups."));
      return panel;
    }
    var wrap = el("div", "table-wrap");
    var table = el("table");
    var caption = el("caption", null, "Totals for " + fmtNumber(totals.itemTypes) + " ItemType(s); "
      + fmtNumber(totals.failedItemTypes) + " did not produce a complete measurement.");
    add(table, caption);
    var thead = el("thead");
    var headerRow = el("tr");
    ["Classification", "ItemTypes", "Failed", "Logical items"].forEach(function (text, index) {
      var th = el("th", index > 0 ? "numeric" : null, text);
      th.scope = "col";
      add(headerRow, th);
    });
    add(thead, headerRow);
    add(table, thead);
    var tbody = el("tbody");
    groups.forEach(function (group) {
      var row = el("tr");
      add(row, el("td", null, group.classification || "Unclassified"));
      add(row, el("td", "numeric", fmtNumber(group.itemTypes)));
      add(row, el("td", "numeric", fmtNumber(group.failedItemTypes)));
      var cell = el("td", "numeric");
      var inner = el("span");
      add(inner, metricNode(group.totalItems));
      if (group.failedItemTypes > 0) {
        add(inner, el("span", "hint", " (covered)"));
      }
      add(cell, inner);
      add(row, cell);
      add(tbody, row);
    });
    add(table, tbody);
    var tfoot = el("tfoot");
    var footRow = el("tr");
    add(footRow, el("td", null, "All classifications"));
    add(footRow, el("td", "numeric", fmtNumber(totals.itemTypes)));
    add(footRow, el("td", "numeric", fmtNumber(totals.failedItemTypes)));
    var footCell = el("td", "numeric");
    add(footCell, totalValueNode(totals.totalItems, !!(snapshot.coverage && snapshot.coverage.complete)));
    add(footRow, footCell);
    add(tfoot, footRow);
    add(table, tfoot);
    add(wrap, table);
    add(panel, wrap);
    return panel;
  }

  function startFullScan(button) {
    button.disabled = true;
    post("/api/statistics/refresh", ACTION_STATISTICS_REFRESH)
      .then(function (result) {
        if (result.status === 202 || result.status === 200) {
          showToast("A full scan was started. The previous snapshot stays visible until the new one is "
            + "published.", "ok");
          return loadStatistics().then(function () {
            if (state.view === "dashboard") {
              render();
            }
            pollScan();
          });
        }
        if (result.status === 409 && result.body && result.body.error
            && result.body.error.code === "scan_in_progress") {
          showToast("A scan is already running; nothing new was started.", "bad");
        } else {
          showToast("The scan was not started: " + errorText(result), "bad");
        }
        button.disabled = false;
      });
  }

  /* ------------------------------------------------------------------ views: item types */

  function viewItemTypes() {
    var host = el("div");
    add(host, pageHead("ItemTypes", "Search, sort and open one ItemType. Activation by keyboard: Tab to a "
      + "row and press Enter or Space, or use the Details button."));

    if (state.itemTypesError) {
      add(host, banner("warn", "ItemTypes could not be read", [state.itemTypesError,
        "This is the documented state when no repository is active or the adapter is unavailable."]));
    }

    var toolbar = el("div", "toolbar");
    var searchField = el("div", "field");
    var searchLabel = el("label", null, "Search");
    searchLabel.htmlFor = "itemtype-search";
    var search = el("input", null, "");
    search.type = "search";
    search.id = "itemtype-search";
    search.value = state.itemTypeFilter;
    search.addEventListener("input", function () {
      state.itemTypeFilter = search.value;
      // Only the table is rebuilt: re-rendering the whole view would replace this input and take the caret
      // away from the operator after the first keystroke.
      fillItemTypeTable();
    });
    add(searchField, searchLabel);
    add(searchField, search);
    add(toolbar, searchField);

    var sortField = el("div", "field");
    var sortLabel = el("label", null, "Sort by");
    sortLabel.htmlFor = "itemtype-sort";
    var sort = el("select");
    sort.id = "itemtype-sort";
    [["name", "Name"], ["classification", "Classification"], ["retention", "Retention policy"],
      ["status", "Statistics status"], ["total", "Logical items"]].forEach(function (option) {
      var item = el("option", null, option[1]);
      item.value = option[0];
      if (state.itemTypeSort === option[0]) {
        item.selected = true;
      }
      add(sort, item);
    });
    sort.addEventListener("change", function () {
      state.itemTypeSort = sort.value;
      fillItemTypeTable();
    });
    add(sortField, sortLabel);
    add(sortField, sort);
    add(toolbar, sortField);

    var direction = el("button", "button", state.itemTypeAscending ? "Ascending" : "Descending");
    direction.type = "button";
    direction.addEventListener("click", function () {
      state.itemTypeAscending = !state.itemTypeAscending;
      direction.textContent = state.itemTypeAscending ? "Ascending" : "Descending";
      fillItemTypeTable();
    });
    add(toolbar, direction);
    var reload = el("button", "button ghost", "Reload");
    reload.type = "button";
    reload.addEventListener("click", function () {
      loadItemTypes().then(function () {
        loadStatistics().then(function () {
          fillItemTypeTable();
        });
      });
    });
    add(toolbar, reload);
    add(host, toolbar);

    itemTypeTableHost = section("ItemTypes",
      "The statistics column reports the LATEST FULL SCAN only. A targeted refresh appears in the ItemType "
      + "properties as separately labelled detail data.");
    add(host, itemTypeTableHost);
    fillItemTypeTable();
    return host;
  }

  /** Rebuilds the ItemTypes table and its count heading in place, leaving the toolbar (and focus) alone. */
  function fillItemTypeTable() {
    var host = itemTypeTableHost;
    if (!host) {
      return;
    }
    clear(host);
    var head = el("div", "panel-head");
    var left = el("div");
    var rows = itemTypeRows();
    add(left, el("h2", null, "ItemTypes (" + rows.length + " of " + state.itemTypes.length + ")"));
    add(left, note("The statistics column reports the LATEST FULL SCAN only. A targeted refresh appears in"
      + " the ItemType properties as separately labelled detail data."));
    add(head, left);
    add(host, head);

    if (!state.itemTypes.length) {
      add(host, note(state.loading ? "Loading ItemTypes..." : "No ItemTypes are available."));
      return;
    }
    if (!rows.length) {
      add(host, note("No ItemType matches the search text."));
      return;
    }

    var wrap = el("div", "table-wrap");
    var table = el("table");
    add(table, el("caption", null, "Rows are ordered by the selected column, then by name, so the order "
      + "is stable across reloads."));
    var thead = el("thead");
    var headRow = el("tr");
    ["Name", "Classification", "Retention policy", "Statistics (full scan)", "Logical items",
      "Details"].forEach(function (text, index) {
      var th = el("th", index >= 3 && index <= 4 ? "numeric" : null, text);
      th.scope = "col";
      add(headRow, th);
    });
    add(thead, headRow);
    add(table, thead);

    var tbody = el("tbody");
    rows.forEach(function (row) {
      var tr = el("tr", "selectable");
      tr.tabIndex = 0;
      tr.setAttribute("role", "button");
      tr.setAttribute("aria-label", "Open properties for ItemType " + row.name);
      tr.addEventListener("click", function (event) {
        if (event.target && event.target.tagName === "BUTTON") {
          return;
        }
        openProperties(row);
      });
      tr.addEventListener("keydown", function (event) {
        if (event.key === "Enter" || event.key === " " || event.key === "Spacebar") {
          event.preventDefault();
          openProperties(row);
        }
      });
      add(tr, el("td", null, row.name));
      add(tr, el("td", null, row.businessClassification || row.classification || "Unclassified"));
      add(tr, el("td", null, row.retentionPolicyName || "none"));
      var statsCell = el("td", "numeric");
      add(statsCell, statisticsCellFor(row));
      add(tr, statsCell);
      var totalCell = el("td", "numeric");
      add(totalCell, itemTypeTotalNode(row));
      add(tr, totalCell);
      var actionCell = el("td");
      var open = el("button", "button ghost", "Details");
      open.type = "button";
      open.addEventListener("click", function () {
        openProperties(row);
      });
      add(actionCell, open);
      add(tr, actionCell);
      add(tbody, tr);
    });
    add(table, tbody);
    add(wrap, table);
    add(host, wrap);
  }

  function statisticsFor(name) {
    var stats = state.statistics;
    if (!stats || !stats.snapshot || !stats.snapshot.present) {
      return null;
    }
    var rows = stats.snapshot.itemTypes || [];
    for (var index = 0; index < rows.length; index++) {
      if (rows[index].name === name) {
        return rows[index];
      }
    }
    return null;
  }

  function statisticsCellFor(row) {
    var stats = statisticsFor(row.name);
    if (!stats) {
      return badge("NOT IN LATEST SCAN", "muted");
    }
    var kind = stats.status === "OK" ? "ok" : stats.status === "PARTIAL" ? "warn" : "bad";
    return badge(stats.status || "UNKNOWN", kind);
  }

  function itemTypeTotalNode(row) {
    var stats = statisticsFor(row.name);
    if (!stats) {
      return badge("UNAVAILABLE", "muted");
    }
    var wrap = el("span");
    add(wrap, metricNode(stats.totalItems));
    var coverage = state.statistics.snapshot.coverage || {};
    if (stats.totalItems && stats.totalItems.available === true && coverage.complete !== true) {
      add(wrap, el("span", "hint", " (covered)"));
    }
    return wrap;
  }

  function itemTypeRows() {
    var filter = state.itemTypeFilter.trim().toLowerCase();
    var rows = state.itemTypes.filter(function (row) {
      if (!filter) {
        return true;
      }
      var haystack = [row.name, row.businessClassification, row.classification, row.retentionPolicyName]
        .join(" ").toLowerCase();
      return haystack.indexOf(filter) >= 0;
    });
    var sortKey = state.itemTypeSort;
    var ascending = state.itemTypeAscending;
    rows.sort(function (left, right) {
      var a;
      var b;
      switch (sortKey) {
        case "classification":
          a = (left.businessClassification || "").toLowerCase();
          b = (right.businessClassification || "").toLowerCase();
          break;
        case "retention":
          a = (left.retentionPolicyName || "").toLowerCase();
          b = (right.retentionPolicyName || "").toLowerCase();
          break;
        case "status":
          a = (statisticsFor(left.name) || {}).status || "ZZZ";
          b = (statisticsFor(right.name) || {}).status || "ZZZ";
          break;
        case "total":
          a = metricValue(statisticsFor(left.name));
          b = metricValue(statisticsFor(right.name));
          break;
        default:
          a = (left.name || "").toLowerCase();
          b = (right.name || "").toLowerCase();
      }
      if (a < b) {
        return ascending ? -1 : 1;
      }
      if (a > b) {
        return ascending ? 1 : -1;
      }
      // A stable tie-break on the name keeps the order deterministic whatever the chosen column is.
      return (left.name || "").toLowerCase() < (right.name || "").toLowerCase() ? -1 : 1;
    });
    return rows;
  }

  function metricValue(stats) {
    if (!stats || !stats.totalItems || stats.totalItems.available !== true) {
      return -1;
    }
    return Number(stats.totalItems.value);
  }

  /* ------------------------------------------------------------------ ItemType properties drawer */

  function openProperties(row) {
    state.selectedItemType = row;
    openDrawer("ItemType " + row.name, function (body) {
      add(body, el("p", "loading", "Reading this ItemType..."));
      api("/api/itemtypes/" + encode(row.name)).then(function (result) {
        clear(body);
        renderProperties(body, row, result);
      });
    });
  }

  function renderProperties(body, row, result) {
    var detail = result.status === 200 && result.body ? result.body.itemType : null;
    var general = section("General");
    if (detail) {
      add(general, definitionList([
        ["Name", detail.name],
        ["ItemType id", fmtNumber(detail.itemTypeId)],
        ["Description", detail.description || "(none)"],
        ["Classification", detail.classification || "(none)"],
        ["Business classification", detail.businessClassification || "(none)"],
        ["Version control", detail.versionControl || "(none)"],
        ["Versioning type", detail.versioningType || "(none)"],
        ["XDO class", detail.xdoClassName || "(none)"],
        ["Default RM", detail.defaultRm ? "yes" : "no"],
        ["Collection code", detail.collectionCode || "(none)"],
        ["Legacy retention summary", detail.legacyRetentionSummary || "(none)"]
      ]));
    } else {
      add(general, banner("warn", "ItemType detail could not be read", [errorText(result)]));
      add(general, definitionList([["Name", row.name], ["ItemType id", fmtNumber(row.itemTypeId)]]));
    }
    add(body, general);
    add(body, propertiesStatistics(row));
    add(body, propertiesRetention(row, detail));
  }

  function propertiesStatistics(row) {
    var panel = section("Statistics",
      "Sourced from the LATEST COMPLETED FULL SCAN, the same snapshot the dashboard totals cite.");
    var stats = statisticsFor(row.name);
    if (!stats) {
      add(panel, banner("warn", "This ItemType is not in the latest full scan",
        ["Either no full scan has completed yet, or this ItemType was added after the scan froze its list."]));
    } else {
      var grid = el("div", "grid");
      add(grid, card("Status", stats.status || "UNKNOWN"));
      add(grid, card("Logical items", metricText(stats.totalItems)));
      add(grid, card("Created today", metricText(stats.today)));
      add(grid, card("Last 7 days", metricText(stats.last7Days)));
      add(grid, card("Last 30 days", metricText(stats.last30Days)));
      add(grid, card("Current year", metricText(stats.currentYear)));
      add(grid, card("Versions", metricText(stats.versions), "UNAVAILABLE in this goal"));
      add(grid, card("Parts", metricText(stats.parts), "UNAVAILABLE in this goal"));
      add(panel, grid);
      if (stats.error) {
        add(panel, note("Recorded reason: " + safeMessage(stats.error), true));
      }
      var snapshot = state.statistics.snapshot || {};
      add(panel, definitionList([
        ["Captured", fmtInstant(snapshot.capturedAt) + " (" + fmtAge(state.statistics.ageMillis) + ")"],
        ["Freshness", freshnessBadge(state.statistics.freshness)],
        ["Coverage", (snapshot.coverage && snapshot.coverage.complete)
          ? "complete - every frozen ItemType was measured"
          : "incomplete - this number is a covered subtotal"]
      ]));
    }
    add(panel, targetedSection(row));
    return panel;
  }

  function targetedSection(row) {
    var wrap = el("div");
    add(wrap, el("h3", null, "Targeted detail data"));
    add(wrap, note("Separate from the totals above. A targeted refresh reads its own database anchor for this "
      + "one ItemType, so it is never merged into the full-scan snapshot or into the dashboard totals."));

    var id = row.itemTypeId;
    var status = el("p", "loading", "Reading targeted detail...");
    add(wrap, status);
    var controls = el("div", "panel-actions");
    var button = el("button", "button", "Refresh this ItemType only");
    button.type = "button";
    button.addEventListener("click", function () {
      runTargetedRefresh(id, button, wrap, status);
    });
    add(controls, button);
    add(wrap, controls);

    api("/api/statistics?itemTypeId=" + encode(id)).then(function (result) {
      clear(status);
      if (result.status !== 200 || !result.body) {
        add(wrap, banner("warn", "Targeted detail could not be read", [errorText(result)]));
        return;
      }
      var targeted = result.body.targeted;
      if (!targeted || targeted.present !== true) {
        add(wrap, banner("warn", "No targeted detail data for this ItemType",
          [targeted && targeted.reason ? safeMessage(targeted.reason)
            : "Use the button above to measure this ItemType alone."]));
        return;
      }
      add(wrap, targetedDetailBlock(targeted));
    });
    return wrap;
  }

  function targetedDetailBlock(targeted) {
    var box = el("div", "card");
    add(box, el("div", "label", "TARGETED DETAIL DATA (" + (targeted.kind || "targeted-detail") + ")"));
    add(box, el("div", "sub", "Measured for this ItemType only. Its own capture time and its own database "
      + "anchor are stated below; the dashboard totals keep citing the latest full scan."));
    add(box, definitionList([
      ["Captured", fmtInstant(targeted.capturedAt) + " (" + fmtAge(targeted.ageMillis) + ")"],
      ["Database anchor", targeted.anchorDate || "unknown"],
      ["Status", targeted.status || "UNKNOWN"],
      ["Freshness", freshnessBadge(targeted.freshness)],
      ["Measurement duration", targeted.durationMs ? fmtDuration(targeted.durationMs) : "unknown"]
    ]));
    var grid = el("div", "grid");
    add(grid, card("Logical items", metricText(targeted.totalItems)));
    add(grid, card("Created today", metricText(targeted.today)));
    add(grid, card("Last 7 days", metricText(targeted.last7Days)));
    add(grid, card("Last 30 days", metricText(targeted.last30Days)));
    add(grid, card("Current year", metricText(targeted.currentYear)));
    add(grid, card("Versions", metricText(targeted.versions), "UNAVAILABLE in this goal"));
    add(grid, card("Parts", metricText(targeted.parts), "UNAVAILABLE in this goal"));
    add(box, grid);
    return box;
  }

  function runTargetedRefresh(itemTypeId, button, container, statusNode) {
    button.disabled = true;
    statusNode.textContent = "Measuring this ItemType...";
    post(ITEM_REFRESH_PREFIX + encode(itemTypeId) + "/refresh", ACTION_ITEM_REFRESH)
      .then(function (result) {
        button.disabled = false;
        clear(statusNode);
        if (result.status === 200 && result.body && result.body.detail) {
          showToast("Targeted refresh completed for ItemType id " + itemTypeId + ".", "ok");
          var block = targetedDetailBlock(result.body.detail);
          container.insertBefore(block, statusNode);
          return;
        }
        var code = result.body && result.body.error ? result.body.error.code : "";
        if (result.status === 409 && code === "analytics_busy") {
          add(statusNode, banner("warn", "Another analytics operation is running",
            ["Nothing was started; the shared analytics-operation gate allows one operation at a time."]));
          return;
        }
        add(statusNode, banner("bad", "The targeted refresh did not complete",
          [errorText(result)]));
      });
  }

  function propertiesRetention(row, detail) {
    var panel = section("Retention", "Read-only: this console never administers retention.");
    var name = (detail && detail.retentionPolicyName) || row.retentionPolicyName || "";
    if (!name) {
      add(panel, note("No retention policy is assigned to this ItemType."));
      return panel;
    }
    add(panel, definitionList([["Policy", name]]));
    if (Array.isArray(state.retention)) {
      var found = null;
      state.retention.forEach(function (policy) {
        if (policy.name === name) {
          found = policy;
        }
      });
      if (found) {
        add(panel, retentionFacts(found));
        return panel;
      }
    }
    var loading = el("p", "loading", "Reading the policy...");
    add(panel, loading);
    api("/api/retention/policies/" + encode(name)).then(function (result) {
      clear(loading);
      if (result.status === 200 && result.body && result.body.policy) {
        add(panel, retentionFacts(result.body.policy));
      } else {
        add(panel, banner("warn", "Policy detail could not be read", [errorText(result)]));
      }
    });
    return panel;
  }

  function retentionFacts(policy) {
    return definitionList([
      ["Description", policy.description || "(none)"],
      ["Policy id", policy.policyId || "(none)"],
      ["Retention type", policy.retentionType || "(none)"],
      ["Retention enabled", policy.retentionEnabled ? "yes" : "no"],
      ["Retention period", policy.retentionPeriod || "(none)"],
      ["Expiration enabled", policy.expirationEnabled ? "yes" : "no"],
      ["Expiration period", policy.expirationPeriod || "(none)"],
      ["Expiration action", policy.expirationAction || "(none)"],
      ["Assigned ItemTypes", (policy.assignedItemTypes || []).length
        ? policy.assignedItemTypes.join(", ") : "(none)"]
    ]);
  }

  /* ------------------------------------------------------------------ views: retention */

  function viewRetention() {
    var host = el("div");
    add(host, pageHead("Retention", "Read-only policy overview from the accepted IBM adapter. There are no "
      + "administration controls on this page."));
    if (state.retentionError) {
      add(host, banner("warn", "Retention policies could not be read", [state.retentionError,
        "This is the documented state when no repository is active or the adapter is unavailable."]));
    }
    var panel = section("Policies (" + (state.retention ? state.retention.length : 0) + ")");
    if (!state.retention || !state.retention.length) {
      add(panel, note(state.loading ? "Loading retention policies..." : "No retention policies are available."));
      add(host, panel);
      return host;
    }
    var wrap = el("div", "table-wrap");
    var table = el("table");
    add(table, el("caption", null, "Every policy is listed with the ItemTypes the adapter assigns to it."));
    var thead = el("thead");
    var headRow = el("tr");
    ["Policy", "Retention", "Expiration", "Assigned ItemTypes"].forEach(function (text) {
      var th = el("th", null, text);
      th.scope = "col";
      add(headRow, th);
    });
    add(thead, headRow);
    add(table, thead);
    var tbody = el("tbody");
    state.retention.forEach(function (policy) {
      var tr = el("tr");
      add(tr, el("td", null, policy.name));
      add(tr, el("td", null, (policy.retentionEnabled ? policy.retentionPeriod || "enabled" : "not enabled")));
      add(tr, el("td", null, policy.expirationEnabled ? (policy.expirationPeriod || "enabled") : "not enabled"));
      add(tr, el("td", null, (policy.assignedItemTypes || []).length
        ? policy.assignedItemTypes.join(", ") : "none"));
      add(tbody, tr);
    });
    add(table, tbody);
    add(wrap, table);
    add(panel, wrap);
    add(host, panel);
    add(host, note("Retention administration stays disabled in this version."));
    return host;
  }

  /* ------------------------------------------------------------------ views: history */

  function viewHistory() {
    var host = el("div");
    var head = pageHead("History", "Stored full snapshots, newest first, served from the local history store "
      + "with a bounded page. Opening a snapshot reads the local store only - no IBM CM and no repository "
      + "database is queried.");
    var actions = el("div", "panel-actions");
    var reload = el("button", "button ghost", "Reload");
    reload.type = "button";
    reload.addEventListener("click", function () {
      state.history.cursorStack = [];
      loadHistory(null).then(render);
    });
    add(actions, reload);
    add(head, actions);
    add(host, head);

    var page = state.history.page;
    if (state.history.error) {
      add(host, banner("warn", "History could not be read", [state.history.error]));
    }
    if (!page) {
      add(host, el("p", "loading", "Reading history..."));
      return host;
    }

    var stateCode = page.state || "UNKNOWN";
    if (stateCode !== "AVAILABLE") {
      add(host, banner(stateCode === "DISABLED" ? "warn" : "bad",
        "History is " + stateCode, [safeMessage(page.reason) || "No reason was reported.",
          "Repository activation, metadata, retention and live statistics are unaffected."]));
      return host;
    }

    var panel = section("Stored snapshots",
      "Page size " + fmtNumber(page.limit) + " (maximum " + fmtNumber(page.maxLimit) + "); "
      + fmtNumber(page.storedCount) + " snapshot(s) stored for " + (page.repositoryId || "this repository")
      + ".");
    if (!(page.entries || []).length) {
      add(panel, note("No snapshot is stored for this repository yet. A full scan that reaches normal "
        + "completion is recorded automatically."));
    } else {
      var wrap = el("div", "table-wrap");
      var table = el("table");
      add(table, el("caption", null, "Newest first. An incomplete snapshot states the coverage it was "
        + "recorded with."));
      var thead = el("thead");
      var headRow = el("tr");
      ["Captured", "Age", "ItemTypes", "Coverage", "Logical items", "Vendor", "Actions"].forEach(function (text, index) {
        var th = el("th", index >= 3 && index <= 4 ? "numeric" : null, text);
        th.scope = "col";
        add(headRow, th);
      });
      add(thead, headRow);
      add(table, thead);
      var tbody = el("tbody");
      page.entries.forEach(function (entry) {
        var tr = el("tr");
        add(tr, el("td", null, fmtInstant(entry.capturedAt)));
        add(tr, el("td", null, fmtAge(entry.ageMillis)));
        add(tr, el("td", "numeric", fmtNumber(entry.itemTypeCount)));
        var coverage = el("td", "numeric");
        add(coverage, entry.complete
          ? badge("COMPLETE", "ok")
          : badge(fmtNumber(entry.partialFailureCount) + " INCOMPLETE", "warn"));
        add(tr, coverage);
        add(tr, el("td", "numeric", fmtNumber(entry.logicalItemsTotal)));
        add(tr, el("td", null, entry.databaseVendor || "unknown"));
        var actionsCell = el("td");
        var openButton = el("button", "button ghost", "Open");
        openButton.type = "button";
        openButton.addEventListener("click", function () {
          openHistoryEntry(entry.id);
        });
        add(actionsCell, openButton);
        add(tr, actionsCell);
        add(tbody, tr);
      });
      add(table, tbody);
      add(wrap, table);
      add(panel, wrap);

      var pager = el("div", "panel-actions spaced-top");
      var previous = el("button", "button", "Previous page");
      previous.type = "button";
      previous.disabled = state.history.cursorStack.length === 0;
      previous.addEventListener("click", function () {
        var stack = state.history.cursorStack.slice();
        stack.pop();
        state.history.cursorStack = stack;
        loadHistory(stack.length ? stack[stack.length - 1] : null).then(render);
      });
      var next = el("button", "button", "Next page");
      next.type = "button";
      next.disabled = !page.hasMore;
      next.addEventListener("click", function () {
        state.history.cursorStack = state.history.cursorStack.concat([page.nextBefore]);
        loadHistory(page.nextBefore).then(render);
      });
      add(pager, previous);
      add(pager, next);
      add(pager, el("span", "hint", "Showing " + fmtNumber(page.returned) + " of "
        + fmtNumber(page.storedCount) + " stored snapshot(s)."));
      add(panel, pager);
    }
    add(host, panel);

    if (state.history.detail) {
      add(host, historyDetailPanel(state.history.detail, state.history.detailId));
    }
    return host;
  }

  function openHistoryEntry(id) {
    api("/api/history/" + encode(id)).then(function (result) {
      if (result.status === 200 && result.body && result.body.entry) {
        state.history.detail = result.body.entry;
        state.history.detailId = id;
        render();
      } else {
        showToast("Snapshot " + id + " could not be opened: " + errorText(result), "bad");
      }
    });
  }

  function historyDetailPanel(entry, id) {
    var summary = entry.summary || {};
    var panel = section("Stored snapshot " + id,
      "Everything below was captured at scan time. Rendering it performs zero IBM CM and zero repository "
      + "database reads.");
    add(panel, definitionList([
      ["Repository", (summary.displayName || summary.repositoryId) + " (" + summary.repositoryId + ")"],
      ["Vendor", summary.databaseVendor || "unknown"],
      ["Captured", fmtInstant(summary.capturedAt) + " (" + fmtAge(summary.ageMillis) + ")"],
      ["Scan started", fmtInstant(summary.scanStartedAt)],
      ["Scan duration", fmtDuration(summary.scanDurationMs)],
      ["Database anchor", summary.anchorDate || "unknown"],
      ["Coverage", summary.complete
        ? "complete - every ItemType of the frozen list was measured"
        : fmtNumber(summary.partialFailureCount) + " of " + fmtNumber(summary.itemTypeCount)
          + " ItemTypes were partial or failed"],
      ["Logical items", fmtNumber(summary.logicalItemsTotal)],
      ["Recorded warning", entry.warning ? safeMessage(entry.warning) : "(none)"]
    ]));

    if (!summary.complete) {
      add(panel, banner("warn", "This stored snapshot is partial",
        ["The logical-items total it carries is a covered subtotal, not a complete repository total."]));
    }

    var actions = el("div", "panel-actions");
    var formatField = el("div", "field");
    var formatLabel = el("label", null, "Report format");
    var formatId = "history-report-format";
    formatLabel.htmlFor = formatId;
    var format = el("select");
    format.id = formatId;
    var formats = (state.reports.list && state.reports.list.formats) || [];
    formats.forEach(function (entry) {
      var option = el("option", null, entry.format.toUpperCase()
        + (entry.available ? "" : " (unavailable)"));
      option.value = entry.format;
      option.disabled = entry.available !== true;
      add(format, option);
    });
    add(formatField, formatLabel);
    add(formatField, format);
    add(actions, formatField);

    var generate = el("button", "button primary", "Generate a report from this snapshot");
    generate.type = "button";
    generate.disabled = !formats.length;
    generate.addEventListener("click", function () {
      generate.disabled = true;
      post(REPORTS_PATH, ACTION_REPORT_GENERATE,
        "source=history&historyId=" + encode(id) + "&format=" + encode(format.value))
        .then(function (result) {
          generate.disabled = false;
          if (result.status === 201 && result.body && result.body.report) {
            state.reports.lastGenerated = result.body.report;
            showToast("Report generated from stored snapshot " + id + ".", "ok");
            loadReports().then(function () {
              if (state.view === "reports") {
                render();
              }
            });
          } else {
            showToast("The report was not generated: " + errorText(result), "bad");
          }
        });
    });
    add(actions, generate);
    add(panel, actions);

    var rows = entry.itemTypes || [];
    if (rows.length) {
      var wrap = el("div", "table-wrap");
      var table = el("table");
      add(table, el("caption", null, "Captured per-ItemType rows (" + rows.length + ")."));
      var thead = el("thead");
      var headRow = el("tr");
      ["ItemType", "Classification", "Retention", "Status", "Logical items", "Today", "7 days", "30 days", "Year"]
        .forEach(function (text, index) {
          var th = el("th", index >= 4 ? "numeric" : null, text);
          th.scope = "col";
          add(headRow, th);
        });
      add(thead, headRow);
      add(table, thead);
      var tbody = el("tbody");
      rows.forEach(function (row) {
        var tr = el("tr");
        add(tr, el("td", null, row.name));
        add(tr, el("td", null, row.classification || "Unclassified"));
        add(tr, el("td", null, row.retentionPolicy || "none"));
        var statusCell = el("td");
        add(statusCell, badge(row.status || "UNKNOWN",
          row.status === "OK" ? "ok" : row.status === "PARTIAL" ? "warn" : "bad"));
        add(tr, statusCell);
        ["logicalItems", "today", "last7Days", "last30Days", "currentYear"].forEach(function (key) {
          var cell = el("td", "numeric");
          add(cell, metricNode(row[key]));
          add(tr, cell);
        });
        add(tbody, tr);
      });
      add(table, tbody);
      add(wrap, table);
      add(panel, wrap);
    }
    return panel;
  }

  /* ------------------------------------------------------------------ views: reports */

  function viewReports() {
    var host = el("div");
    add(host, pageHead("Reports", "Available formats, generated artifacts and safe downloads. A report is "
      + "written below the configured reports directory under an opaque generated id; this page never builds a "
      + "file path from a request value."));

    if (state.reports.error) {
      add(host, banner("bad", "Reports could not be listed", [state.reports.error]));
    }
    var list = state.reports.list;

    var formatsPanel = section("Formats this build declares",
      "An unavailable format stays visible as unavailable. It is never omitted and never replaced by another "
      + "format.");
    var formats = list && list.formats ? list.formats : [];
    if (!formats.length) {
      add(formatsPanel, note(list
        ? "This runtime declares no report format."
        : "The declared formats could not be read; the error above explains why."));
    } else {
      var formatsWrap = el("div", "table-wrap");
      var formatsTable = el("table");
      var fthead = el("thead");
      var fheadRow = el("tr");
      ["Format", "Availability", "What it produces"].forEach(function (text) {
        var th = el("th", null, text);
        th.scope = "col";
        add(fheadRow, th);
      });
      add(fthead, fheadRow);
      add(formatsTable, fthead);
      var ftbody = el("tbody");
      formats.forEach(function (entry) {
        var tr = el("tr");
        add(tr, el("td", null, entry.format.toUpperCase()));
        var cell = el("td");
        add(cell, entry.available ? badge("AVAILABLE", "ok") : badge("UNAVAILABLE", "warn"));
        add(tr, cell);
        add(tr, el("td", null, entry.detail || ""));
        add(ftbody, tr);
      });
      add(formatsTable, ftbody);
      add(formatsWrap, formatsTable);
      add(formatsPanel, formatsWrap);
    }
    if (list && list.available !== true) {
      add(formatsPanel, banner("warn", "The report capability is unavailable",
        [safeMessage(list.reason) || "No reason was reported."]));
    }
    add(host, formatsPanel);

    add(host, generateReportPanel(formats));
    add(host, reportsListPanel(list));
    return host;
  }

  function generateReportPanel(formats) {
    var panel = section("Generate a report",
      "Generation writes a local file, so it requires the report-generate action header, which this console "
      + "sends only when you press the button.");
    if (!formats.length) {
      add(panel, banner("warn", "No report format is available",
        ["The declared formats could not be read, so this console will not guess one."]));
      return panel;
    }
    var toolbar = el("div", "toolbar");

    var sourceField = el("div", "field");
    var sourceLabel = el("label", null, "Source");
    sourceLabel.htmlFor = "report-source";
    var source = el("select");
    source.id = "report-source";
    [["current", "Current completed full snapshot"], ["history", "Stored history snapshot"]]
      .forEach(function (option) {
        var item = el("option", null, option[1]);
        item.value = option[0];
        add(source, item);
      });
    add(sourceField, sourceLabel);
    add(sourceField, source);

    var historyField = el("div", "field");
    var historyLabel = el("label", null, "Stored snapshot id");
    historyLabel.htmlFor = "report-history-id";
    var historyId = el("input", null, "");
    historyId.type = "text";
    historyId.id = "report-history-id";
    historyId.placeholder = "open a snapshot in History to copy its id";
    historyId.disabled = true;
    add(historyField, historyLabel);
    add(historyField, historyId);

    var formatField = el("div", "field");
    var formatLabel = el("label", null, "Format");
    formatLabel.htmlFor = "report-format";
    var format = el("select");
    format.id = "report-format";
    formats.forEach(function (entry) {
      var option = el("option", null, entry.format.toUpperCase()
        + (entry.available === false ? " (unavailable)" : ""));
      option.value = entry.format;
      option.disabled = entry.available === false;
      add(format, option);
    });
    add(formatField, formatLabel);
    add(formatField, format);

    source.addEventListener("change", function () {
      var isHistory = source.value === "history";
      historyId.disabled = !isHistory;
    });
    if (state.history.detailId && state.history.detail) {
      historyId.value = state.history.detailId;
    }

    add(toolbar, sourceField);
    add(toolbar, historyField);
    add(toolbar, formatField);

    var button = el("button", "button primary", "Generate");
    button.type = "button";
    button.addEventListener("click", function () {
      button.disabled = true;
      var query = "source=" + encode(source.value) + "&format=" + encode(format.value);
      if (source.value === "history") {
        query += "&historyId=" + encode(historyId.value.trim());
      }
      post(REPORTS_PATH, ACTION_REPORT_GENERATE, query).then(function (result) {
        button.disabled = false;
        if (result.status === 201 && result.body && result.body.report) {
          state.reports.lastGenerated = result.body.report;
          showToast("Report generated: " + result.body.report.fileName, "ok");
          loadReports().then(render);
          return;
        }
        var hint = "";
        if (result.status === 409) {
          hint = " A current report needs an active repository with a completed full scan.";
        }
        if (result.status === 503) {
          hint = " The format or the output directory is not usable.";
        }
        showToast("The report was not generated: " + errorText(result) + hint, "bad");
      });
    });
    add(toolbar, button);
    add(panel, toolbar);
    add(panel, note("A report renders only values that were already captured: nothing here queries IBM CM or "
      + "the repository database to fill a gap."));
    return panel;
  }

  function reportsListPanel(list) {
    var entries = list && list.entries ? list.entries : [];
    var panel = section("Generated reports (" + entries.length + ")",
      "Newest first, bounded list. Downloads are attachments served from this application's own reports "
      + "directory.");
    if (!entries.length) {
      add(panel, note("No report has been generated yet."));
      return panel;
    }
    var wrap = el("div", "table-wrap");
    var table = el("table");
    var thead = el("thead");
    var headRow = el("tr");
    ["Written", "Age", "Format", "Size", "File", "Download"].forEach(function (text, index) {
      var th = el("th", index === 3 ? "numeric" : null, text);
      th.scope = "col";
      add(headRow, th);
    });
    add(thead, headRow);
    add(table, thead);
    var tbody = el("tbody");
    entries.forEach(function (entry) {
      var tr = el("tr");
      add(tr, el("td", null, fmtInstant(entry.writtenAt)));
      add(tr, el("td", null, fmtAge(entry.ageMillis)));
      add(tr, el("td", null, String(entry.format).toUpperCase()));
      add(tr, el("td", "numeric", fmtBytes(entry.sizeBytes)));
      add(tr, el("td", "break mono", entry.fileName));
      var cell = el("td");
      var link = el("a", "button ghost", "Download");
      link.href = REPORTS_PATH + "/" + encode(entry.id) + "/download?format=" + encode(entry.format);
      link.setAttribute("download", entry.fileName);
      add(cell, link);
      add(tr, cell);
      add(tbody, tr);
    });
    add(table, tbody);
    add(wrap, table);
    add(panel, wrap);
    return panel;
  }

  /* ------------------------------------------------------------------ views: system */

  function viewSystem() {
    var host = el("div");
    add(host, pageHead("System and diagnostics", "Safe facts only. This view never shows a credential, a JDBC "
      + "URL or user, raw SQL, raw exception text or a secret path - the payloads behind it have no field for "
      + "any of them."));

    var info = section("Application");
    add(info, el("p", "loading", "Reading application facts..."));
    api("/api/info").then(function (result) {
      clear(info);
      add(info, el("h2", null, "Application"));
      if (result.status === 200 && result.body) {
        add(info, definitionList([
          ["Service", result.body.name || "CM Insight"],
          ["Version", result.body.version || "unknown"],
          ["Mode", result.body.mode || "unknown"],
          ["Java version", result.body.javaVersion || "unknown"],
          ["Versions and Parts", "UNAVAILABLE in this goal"]
        ]));
      } else {
        add(info, banner("bad", "Application facts could not be read", [errorText(result)]));
      }
    });
    add(host, info);

    var health = section("Health");
    add(health, el("p", "loading", "Reading health..."));
    api("/api/health").then(function (result) {
      clear(health);
      add(health, el("h2", null, "Health"));
      if (result.status === 200 && result.body) {
        add(health, definitionList([["Status", result.body.status || "unknown"],
          ["Service", result.body.service || "unknown"]]));
      } else {
        add(health, banner("bad", "Health could not be read", [errorText(result)]));
      }
    });
    add(host, health);

    add(host, lifecyclePanel());
    add(host, cmPanel());
    add(host, jdbcPanel());
    add(host, capabilitiesPanel());
    return host;
  }

  function lifecyclePanel() {
    var panel = section("Repository lifecycle");
    add(panel, el("p", "loading", "Reading lifecycle..."));
    api("/api/repositories/status").then(function (result) {
      clear(panel);
      add(panel, el("h2", null, "Repository lifecycle"));
      if (result.status !== 200 || !result.body) {
        add(panel, banner("bad", "Lifecycle could not be read", [errorText(result)]));
        return;
      }
      var body = result.body;
      var grid = el("div", "grid");
      add(grid, card("State", body.state + " - " + (body.stateDescription || "")));
      add(grid, card("Usable", body.usable ? "yes" : "no"));
      add(grid, card("Active repository", body.repositoryId || "none"));
      add(grid, card("Recorded refusal", body.refusal || "none"));
      add(grid, card("Context close state", body.contextCloseState || "not available"));
      add(grid, card("Retained context", body.retainedContext || "none"));
      add(panel, grid);
    });
    return panel;
  }

  function cmPanel() {
    var panel = section("IBM CM adapter, pool and metadata cache");
    add(panel, el("p", "loading", "Reading CM diagnostics..."));
    api("/api/diagnostics/cm").then(function (result) {
      clear(panel);
      add(panel, el("h2", null, "IBM CM adapter, pool and metadata cache"));
      if (result.status !== 200 || !result.body) {
        add(panel, banner("bad", "CM diagnostics could not be read", [errorText(result)]));
        return;
      }
      var body = result.body;
      var adapter = body.adapter || {};
      var repository = body.repository || {};
      var pool = body.pool || {};
      var cache = body.cache || {};

      add(panel, definitionList([
        ["Adapter availability", adapter.availability || "UNKNOWN"],
        ["Provider id", adapter.providerId || "(none installed)"],
        ["Adapter version", adapter.adapterVersion || "unknown"],
        ["IBM CM API release", adapter.sdkRelease || "unknown"],
        ["Repository state", (repository.state || "unknown") + (repository.active ? " (active)" : "")],
        ["Refusal", repository.refusal || "none"]
      ]));

      var grid = el("div", "grid");
      if (pool.present) {
        add(grid, card("CM pool", pool.poolName || "(unnamed)"));
        add(grid, card("Capacity in use", fmtNumber(pool.capacityInUse) + " / " + fmtNumber(pool.configuredSize)));
        add(grid, card("Leased / available", fmtNumber(pool.leased) + " / " + fmtNumber(pool.available)));
        add(grid, card("Quarantined slots", fmtNumber(pool.quarantined),
          pool.degraded ? "the pool reports degraded capacity" : "no permanent capacity loss reported"));
        add(grid, card("Borrow timeouts", fmtNumber(pool.borrowTimeoutCount)));
        add(grid, card("Create failures", fmtNumber(pool.createFailures)));
      } else {
        add(grid, card("CM pool", "not present", "no repository is active"));
      }
      if (cache.present) {
        add(grid, card("Metadata cache TTL", fmtNumber(cache.ttlSeconds) + " s",
          "caching " + (cache.caching ? "on" : "off")));
        add(grid, card("Metadata snapshot age", cache.snapshotLoaded ? fmtAge(cache.ageMillis) : "none loaded"));
        add(grid, card("Metadata freshness", cache.fresh ? "fresh" : "stale"));
        add(grid, card("Last load failed", cache.loadFailed ? "yes" : "no"));
      } else {
        add(grid, card("Metadata cache", "not present", "no repository is active"));
      }
      add(panel, grid);
    });
    return panel;
  }

  function jdbcPanel() {
    var panel = section("Analytics database readiness");
    add(panel, el("p", "loading", "Reading JDBC diagnostics..."));
    api("/api/diagnostics/jdbc").then(function (result) {
      clear(panel);
      add(panel, el("h2", null, "Analytics database readiness"));
      if (result.status !== 200 || !result.body) {
        add(panel, banner("bad", "JDBC diagnostics could not be read", [errorText(result)]));
        return;
      }
      var body = result.body;
      var driver = body.driver || {};
      var schema = body.schema || {};
      var pool = body.pool || {};
      var scan = body.scan || {};
      add(panel, definitionList([
        ["Analytics state", body.state + (body.available ? " (usable)" : "")],
        ["Reason", safeMessage(body.reason) || "(none)"],
        ["Database vendor", body.vendor || "unknown"],
        ["Driver installed", driver.installed ? "yes" : "no"],
        ["Driver ready", driver.ready ? "yes" : "no"],
        ["Driver identity", driver.identity || "(unknown)"],
        ["URL family matches vendor", driver.vendorUrlMatches ? "yes" : "no"],
        ["Schema source", schema.source || "UNKNOWN"],
        ["Scan phase", (scan.running ? "RUNNING - " : "") + (scan.phase || "IDLE")],
        ["Last scan reason", safeMessage(scan.reason) || "(none)"]
      ]));

      var grid = el("div", "grid");
      if (pool.present) {
        add(grid, card("Analytics pool", pool.poolName || "(unnamed)"));
        add(grid, card("Live connections", fmtNumber(pool.liveConnections),
          "peak " + fmtNumber(pool.peakLiveConnections) + ", opened " + fmtNumber(pool.openedConnections)));
        add(grid, card("Capacity in use", fmtNumber(pool.capacityInUse) + " / " + fmtNumber(pool.configuredSize)));
        add(grid, card("Borrow timeouts", fmtNumber(pool.borrowTimeoutCount)));
        add(grid, card("Close failures", fmtNumber(pool.closeFailures),
          "close state " + (pool.closeState || "unknown")));
      } else {
        add(grid, card("Analytics pool", "not present", "analytics is unavailable or disabled"));
      }
      add(panel, grid);

      if (body.lastJdbcError) {
        add(panel, definitionList([
          ["Last failure operation", body.lastJdbcError.operation || "(unspecified)"],
          ["SQLState", body.lastJdbcError.sqlState || "(none)"],
          ["Vendor code", body.lastJdbcError.vendorCode || "(none)"],
          ["Summary", safeMessage(body.lastJdbcError.summary) || "(none)"]
        ]));
        add(panel, note("Failure text is sanitized before it is published: no raw driver message, URL, user or "
          + "SQL statement can appear here."));
      }
    });
    return panel;
  }

  function capabilitiesPanel() {
    var panel = section("History, reports and statistics freshness");
    add(panel, el("p", "loading", "Reading capability states..."));
    Promise.all([api("/api/history?limit=1"), api(REPORTS_PATH + "?limit=1")]).then(function (results) {
      clear(panel);
      add(panel, el("h2", null, "History, reports and statistics freshness"));
      var history = results[0];
      var reports = results[1];
      var rows = [];
      if (history.status === 200 && history.body) {
        rows.push(["History capability", (history.body.state || "UNKNOWN")
          + (history.body.enabled ? " (feature enabled)" : " (feature disabled)")]);
        rows.push(["History reason", safeMessage(history.body.reason) || "(none)"]);
        rows.push(["Stored snapshots", fmtNumber(history.body.storedCount)]);
      } else {
        rows.push(["History capability", "could not be read: " + errorText(history)]);
      }
      if (reports.status === 200 && reports.body) {
        rows.push(["Report capability", (reports.body.state || "UNKNOWN")]);
        rows.push(["Report reason", safeMessage(reports.body.reason) || "(none)"]);
        var formats = (reports.body.formats || []).map(function (entry) {
          return entry.format.toUpperCase() + (entry.available ? "" : " (unavailable)");
        });
        rows.push(["Report formats", formats.join(", ") || "(none declared)"]);
      } else {
        rows.push(["Report capability", "could not be read: " + errorText(reports)]);
      }
      var stats = state.statistics;
      if (stats) {
        rows.push(["Statistics freshness", (stats.freshness && stats.freshness.state) || "UNKNOWN"]);
        rows.push(["Freshness threshold", fmtNumber(stats.freshness ? stats.freshness.thresholdSeconds : 0) + " s"]);
      }
      add(panel, definitionList(rows));
    });
    return panel;
  }

  /* ------------------------------------------------------------------ page head helper */

  function pageHead(title, lede) {
    var head = el("div", "page-head");
    var left = el("div");
    add(left, el("h1", null, title));
    add(left, el("p", "lede", lede));
    add(head, left);
    return head;
  }

  /* ------------------------------------------------------------------ renderer */

  function render() {
    var host = el("div");
    switch (state.view) {
      case "repository":
        host = viewRepository();
        break;
      case "itemtypes":
        host = viewItemTypes();
        break;
      case "retention":
        host = viewRetention();
        break;
      case "history":
        host = viewHistory();
        break;
      case "reports":
        host = viewReports();
        break;
      case "system":
        host = viewSystem();
        break;
      default:
        host = viewDashboard();
    }
    clear(viewHost);
    add(viewHost, host);
    setActiveNav(state.view);
    updateTopFacts();
  }

  function navigate(view) {
    if (VIEWS.indexOf(view) < 0) {
      view = "dashboard";
    }
    state.view = view;
    if (window.location.hash !== "#" + view) {
      window.location.hash = view;
    }
    loadForView(view).then(null, function () {
      return null;
    });
  }

  function loadForView(view) {
    switch (view) {
      case "repository":
        return loadRepositories().then(function () {
          render();
        });
      case "dashboard":
        return loadStatistics().then(function () {
          render();
          pollScan();
        });
      case "itemtypes":
        return Promise.all([loadItemTypes(), loadStatistics()]).then(function () {
          render();
        });
      case "retention":
        return loadRetention().then(function () {
          render();
        });
      case "history":
        return Promise.all([
          loadHistory(state.history.cursorStack.length
            ? state.history.cursorStack[state.history.cursorStack.length - 1] : null),
          // The formats are needed to offer "generate a report from this snapshot"; loading them here keeps
          // the History view from inventing a format it cannot know.
          loadReports()
        ]).then(function () {
          render();
        });
      case "reports":
        return Promise.all([loadReports(), loadRetention()]).then(function () {
          render();
        });
      default:
        return refreshChrome().then(function () {
          render();
        });
    }
  }

  function refreshAll() {
    return Promise.all([loadRepositories(), loadStatistics()]).then(function () {
      return loadForView(state.view).then(null, function () {
        return null;
      });
    });
  }

  /* ------------------------------------------------------------------ boot */

  function boot() {
    document.querySelectorAll(".nav-button").forEach(function (button) {
      button.addEventListener("click", function () {
        navigate(button.getAttribute("data-view"));
      });
    });
    document.getElementById("drawer-close").addEventListener("click", closeDrawer);
    drawerBackdrop.addEventListener("click", closeDrawer);
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !drawer.hidden) {
        closeDrawer();
      }
    });
    window.addEventListener("hashchange", function () {
      var requested = window.location.hash.replace("#", "");
      if (VIEWS.indexOf(requested) >= 0 && requested !== state.view) {
        state.view = requested;
        loadForView(requested);
      }
    });

    var requested = window.location.hash.replace("#", "");
    state.view = VIEWS.indexOf(requested) >= 0 ? requested : "dashboard";
    state.loading = true;
    refreshChrome().then(function () {
      state.loading = false;
      return loadForView(state.view);
    }).then(null, function () {
      state.loading = false;
      render();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
