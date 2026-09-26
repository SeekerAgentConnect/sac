// Small progressive enhancements for the server-rendered admin. This file holds no credential,
// sends nothing, and reads no state that the gateway did not already render on the page.
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

  var drawer = document.querySelector("[data-drawer]");
  var drawerFocus = null;
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

  function openDrawer(opener) {
    if (!drawer) return false;
    drawerFocus = opener || document.activeElement;
    drawer.classList.add("is-open");
    drawer.setAttribute("aria-hidden", "false");
    document.body.classList.add("drawer-open");
    if (window.location.hash !== "#add-server") {
      window.history.replaceState(
        null,
        "",
        window.location.pathname + window.location.search + "#add-server",
      );
    }
    var first = drawer.querySelector(
      "input:not([type=hidden]), button, select",
    );
    if (first) first.focus();
    return true;
  }

  function closeDrawer() {
    if (!drawer) return;
    drawer.classList.remove("is-open");
    drawer.setAttribute("aria-hidden", "true");
    document.body.classList.remove("drawer-open");
    if (window.location.hash === "#add-server") {
      window.history.replaceState(
        null,
        "",
        window.location.pathname + window.location.search,
      );
    }
    if (drawerFocus && drawerFocus.focus) drawerFocus.focus();
    drawerFocus = null;
  }

  document.addEventListener("click", function (event) {
    var opener = event.target.closest("[data-open-drawer]");
    if (opener && openDrawer(opener)) {
      event.preventDefault();
      return;
    }
    if (event.target.closest("[data-close-drawer]") && drawer) {
      event.preventDefault();
      closeDrawer();
    }
  });

  if (
    drawer &&
    (drawer.classList.contains("is-open") ||
      window.location.hash === "#add-server")
  ) {
    openDrawer(null);
  }

  document.addEventListener("keydown", function (event) {
    if (!drawer || !drawer.classList.contains("is-open")) return;
    if (event.key === "Escape") closeDrawer();
    else trapFocus(drawer, event);
  });

  var generate = drawer && drawer.querySelector('input[name="generate"]');
  var generatedField = drawer && drawer.querySelector("[data-generated-field]");
  var serverID =
    generatedField && generatedField.querySelector('input[name="server"]');

  function reflectGenerated() {
    if (!generate || !generatedField || !serverID) return;
    generatedField.classList.toggle("generated", generate.checked);
    serverID.disabled = generate.checked;
  }

  if (generate) {
    reflectGenerated();
    generate.addEventListener("change", reflectGenerated);
  }

  document.querySelectorAll("[data-row-href]").forEach(function (row) {
    function follow(event) {
      if (event.target.closest("a, button, input, select, label")) return;
      window.location.assign(row.getAttribute("data-row-href"));
    }
    row.addEventListener("click", follow);
    row.addEventListener("keydown", function (event) {
      if (event.key === "Enter") follow(event);
    });
  });

  document.querySelectorAll("[data-dirty-form]").forEach(function (form) {
    var submit = form.querySelector("[data-dirty-submit]");
    var controls = Array.from(form.querySelectorAll('input[type="checkbox"]'));
    var initial = controls
      .map(function (input) {
        return input.checked;
      })
      .join("|");
    function update() {
      var current = controls
        .map(function (input) {
          return input.checked;
        })
        .join("|");
      submit.disabled = current === initial;
    }
    controls.forEach(function (input) {
      input.addEventListener("change", update);
    });
    update();
  });

  document.querySelectorAll("[data-match-input]").forEach(function (button) {
    var form = button.form;
    var input = form && form.elements[button.getAttribute("data-match-input")];
    var wanted = button.getAttribute("data-match-value") || "";
    function update() {
      button.disabled = !input || input.value.trim() !== wanted;
    }
    if (input) input.addEventListener("input", update);
    update();
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
    if (allowedForm === event.target) {
      allowedForm = null;
      return;
    }
    var submitter = event.submitter;
    var question = submitter && submitter.getAttribute("data-confirm");
    if (!question || !dialog || !message || !run || !cancel) return;
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
      else HTMLFormElement.prototype.submit.call(form);
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
})();
