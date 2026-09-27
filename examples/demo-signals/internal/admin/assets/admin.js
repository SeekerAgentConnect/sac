// Small progressive enhancements for the server-rendered admin. This file holds no credential,
// sends nothing, and reads no state that the page did not already render. Every action is still an
// ordinary form post, so the page works the same with it switched off.
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
})();
