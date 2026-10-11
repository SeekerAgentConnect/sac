// Small progressive enhancements for the server-rendered admin. This file holds no credential,
// sends nothing, and reads no state that the page did not already render. Every action is still an
// ordinary form post, so the page works the same with it switched off.
//
// The one thing it keeps is the operator's last market search: its filters and the markets it
// found, in this browser's localStorage, so publishing one market and coming back to the page does
// not lose the list the next one is picked from. Both are public listing data.
(function () {
  "use strict";

  var copyStates = new WeakMap();

  function copied(button, ok) {
    var label = button.querySelector(".copy-label") || button;
    var glyph = button.querySelector(".copy-glyph");
    var state = copyStates.get(button);
    if (!state) {
      state = {
        label: label.textContent,
        glyph: glyph && glyph.textContent,
        timer: null,
      };
      copyStates.set(button, state);
    }
    window.clearTimeout(state.timer);
    label.textContent = ok ? "Copied" : "Press ⌘/Ctrl+C";
    if (glyph && ok) glyph.textContent = "✓";
    state.timer = window.setTimeout(function () {
      label.textContent = state.label;
      if (glyph) glyph.textContent = state.glyph;
      state.timer = null;
    }, 1500);
  }

  document.addEventListener("click", function (event) {
    var button = event.target.closest("button.copy");
    if (!button) return;
    var value = button.getAttribute("data-copy") || "";
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(value).then(
        function () {
          copied(button, true);
        },
        function () {
          copied(button, false);
        },
      );
      return;
    }
    var holder = button.previousElementSibling;
    if (holder && window.getSelection) {
      var range = document.createRange();
      range.selectNodeContents(holder);
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
    copied(button, false);
  });

  var focusableSelector =
    'a[href]:not([tabindex="-1"]), button:not([disabled]), input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

  function trapFocus(container, event) {
    if (event.key !== "Tab") return;
    var controls = Array.from(
      container.querySelectorAll(focusableSelector),
    ).filter(function (control) {
      return control.getClientRects().length > 0;
    });
    if (!controls.length) return;
    var first = controls[0];
    var last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  // The busy mask: a call to the publisher's API can take a while, and a second click would send
  // the same action twice.
  var mask = document.querySelector(".busy-mask");
  var maskLabel = mask && mask.querySelector("span");

  function markBusy(submitter) {
    document.body.classList.add("busy");
    var wording = submitter && submitter.getAttribute("data-busy");
    if (maskLabel && wording) maskLabel.textContent = wording;
  }

  // A page restored from the back-forward cache is not busy any more.
  window.addEventListener("pageshow", function () {
    document.body.classList.remove("busy");
  });

  var dialog = document.getElementById("confirmation-dialog");
  var message = dialog && dialog.querySelector("#confirmation-message");
  var run = dialog && dialog.querySelector("[data-run-confirm]");
  var cancel = dialog && dialog.querySelector("[data-cancel-confirm]");
  var pendingForm = null;
  var pendingSubmitter = null;
  var confirmFocus = null;
  var allowedForm = null;

  function clearConfirmation() {
    pendingForm = null;
    pendingSubmitter = null;
    document.body.classList.remove("dialog-open");
    if (confirmFocus && confirmFocus.focus) confirmFocus.focus();
    confirmFocus = null;
  }

  function closeConfirmation() {
    if (typeof dialog.close === "function" && dialog.open) {
      dialog.close();
      return;
    }
    dialog.removeAttribute("open");
    dialog.classList.remove("is-fallback");
    clearConfirmation();
  }

  document.addEventListener("submit", function (event) {
    var submitter = event.submitter;
    if (allowedForm === event.target) {
      allowedForm = null;
      markBusy(submitter);
      return;
    }
    var question = submitter && submitter.getAttribute("data-confirm");
    if (!question || !dialog || !message || !run || !cancel) {
      markBusy(submitter);
      return;
    }
    event.preventDefault();
    pendingForm = event.target;
    pendingSubmitter = submitter;
    confirmFocus = submitter;
    message.textContent = question;
    run.textContent = submitter.getAttribute("data-confirm-label") || "Confirm";
    if (typeof dialog.showModal === "function") dialog.showModal();
    else {
      dialog.setAttribute("open", "");
      dialog.classList.add("is-fallback");
      document.body.classList.add("dialog-open");
    }
    cancel.focus();
  });

  if (dialog) {
    cancel.addEventListener("click", function () {
      closeConfirmation();
    });
    run.addEventListener("click", function () {
      var form = pendingForm;
      var submitter = pendingSubmitter;
      pendingForm = null;
      pendingSubmitter = null;
      closeConfirmation();
      if (!form) return;
      allowedForm = form;
      if (form.requestSubmit) form.requestSubmit(submitter);
      else {
        markBusy(submitter);
        HTMLFormElement.prototype.submit.call(form);
      }
    });
    dialog.addEventListener("click", function (event) {
      if (event.target === dialog) closeConfirmation();
    });
    dialog.addEventListener("keydown", function (event) {
      if (event.key === "Escape") {
        event.preventDefault();
        closeConfirmation();
      } else {
        trapFocus(dialog, event);
      }
    });
    dialog.addEventListener("close", function () {
      clearConfirmation();
    });
  }

  // The saved search. The filters and the results are kept apart so clearing the markets keeps the
  // filters that found them.
  var filterKey = "prediction-admin.search.filter.v1";
  var resultsKey = "prediction-admin.search.results.v1";
  var searchForm = document.querySelector("form[data-market-search]");
  var resultsSlot = document.querySelector("[data-market-results]");
  var filterFields = [
    "source",
    "category",
    "filter",
    "keywords",
    "tags",
    "state",
    "least_close_minutes",
    "most_close_minutes",
  ];

  function readSaved(key) {
    try {
      var raw = window.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    } catch (ignored) {
      return null;
    }
  }

  function writeSaved(key, value) {
    try {
      if (value === null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, JSON.stringify(value));
    } catch (ignored) {
      // Private windows and blocked storage: the page still works, it just does not remember.
    }
  }

  function currentFilter() {
    var saved = {};
    filterFields.forEach(function (name) {
      var field = searchForm.elements.namedItem(name);
      if (field) saved[name] = field.value;
    });
    return saved;
  }

  function applyFilter(saved) {
    filterFields.forEach(function (name) {
      var field = searchForm.elements.namedItem(name);
      if (field && typeof saved[name] === "string") field.value = saved[name];
    });
  }

  function element(tag, className, text) {
    var made = document.createElement(tag);
    if (className) made.className = className;
    if (text) made.textContent = text;
    return made;
  }

  // The markets on the feed now, as the published table rendered them, so a restored list says
  // which ones a publish or an unpublish has changed since it was saved.
  function openMarkets() {
    var open = {};
    document.querySelectorAll("[data-open-market]").forEach(function (row) {
      open[row.getAttribute("data-open-market")] = true;
    });
    return open;
  }

  function clearButton() {
    var clear = element("a", "button secondary small", "Clear markets");
    clear.href = searchForm.getAttribute("action") + "#market-search";
    clear.setAttribute("data-clear-markets", "");
    return clear;
  }

  function renderSaved(saved) {
    var open = openMarkets();
    var head = element("div", "results-head");
    var when = saved.at
      ? " Saved " + new Date(saved.at).toLocaleString() + "."
      : "";
    head.appendChild(
      element("p", "hint after", "Showing up to 10 matching markets." + when),
    );
    head.appendChild(clearButton());
    resultsSlot.appendChild(head);
    if (!saved.markets.length) {
      var empty = element("div", "empty-state");
      empty
        .appendChild(element("span", "empty-glyph", "⌕"))
        .setAttribute("aria-hidden", "true");
      empty.appendChild(element("p", "", "No markets matched those filters."));
      resultsSlot.appendChild(empty);
      return;
    }
    var card = element("div", "table-card");
    var scroll = card.appendChild(element("div", "table-scroll"));
    var table = scroll.appendChild(element("table", "list"));
    var headRow = table
      .appendChild(element("thead"))
      .appendChild(element("tr"));
    ["Market", "Category", "Closes", ""].forEach(function (label) {
      headRow.appendChild(element("th", "", label));
    });
    var body = table.appendChild(element("tbody"));
    saved.markets.forEach(function (market) {
      var published = !!open[market.MarketID];
      var dismissed = !published && (market.Dismissed || market.Published);
      var row = body.appendChild(element("tr"));
      var about = row.appendChild(element("td"));
      about.appendChild(
        element(
          "span",
          "cell-title",
          (market.EventTitle ? market.EventTitle + " — " : "") + market.Title,
        ),
      );
      about.appendChild(element("code", "id block", market.MarketID));
      if (published)
        about.appendChild(element("span", "pill neutral", "already published"));
      else if (dismissed)
        about.appendChild(element("span", "pill warn", "unpublished"));
      var category = row.appendChild(element("td", "", market.Category));
      if (market.Tags)
        category.appendChild(element("span", "sub block", market.Tags));
      row
        .appendChild(element("td"))
        .appendChild(element("span", "sub nowrap", market.CloseAt));
      var actions = row.appendChild(element("td"));
      if (published) return;
      var form = actions
        .appendChild(element("div", "row-actions"))
        .appendChild(element("form"));
      form.method = "post";
      form.action = searchForm.getAttribute("action") + "/select";
      var id = form.appendChild(element("input"));
      id.type = "hidden";
      id.name = "market_id";
      id.value = market.MarketID;
      var publish = form.appendChild(
        element("button", "button primary small", "Publish to the feed"),
      );
      publish.type = "submit";
      publish.setAttribute("data-busy", "Publishing…");
    });
    resultsSlot.appendChild(card);
  }

  if (searchForm && resultsSlot) {
    var fresh = document.getElementById("market-results");
    if (fresh) {
      // A search just ran: what the form searched for and what it found are the new saved search.
      var found = [];
      try {
        found = JSON.parse(fresh.textContent) || [];
      } catch (ignored) {
        found = [];
      }
      writeSaved(filterKey, currentFilter());
      writeSaved(resultsKey, { at: Date.now(), markets: found });
    } else {
      var params = new URLSearchParams(window.location.search);
      var asked = filterFields.some(function (name) {
        return params.get(name);
      });
      var filter = readSaved(filterKey);
      if (filter && !asked) applyFilter(filter);
      var saved = readSaved(resultsKey);
      if (saved && Array.isArray(saved.markets)) renderSaved(saved);
    }

    searchForm.addEventListener("submit", function () {
      writeSaved(filterKey, currentFilter());
    });

    document.addEventListener("click", function (event) {
      var clear = event.target.closest("[data-clear-markets]");
      if (!clear) return;
      event.preventDefault();
      writeSaved(resultsKey, null);
      while (resultsSlot.firstChild)
        resultsSlot.removeChild(resultsSlot.firstChild);
      // Drop the search from the address too, so a reload does not run it again.
      if (window.history && window.history.replaceState) {
        window.history.replaceState(null, "", clear.getAttribute("href"));
      }
    });
  }
})();
