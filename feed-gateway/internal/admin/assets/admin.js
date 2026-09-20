// Two conveniences, and nothing else. This file holds no credential, sends nothing anywhere and
// reads no state: every value it copies is already on the page, put there by the gateway that
// rendered it. The page works without it — a credential can be selected and copied by hand, and a
// destructive form submits on its own — which is why it is `defer`red and guarded rather than
// depended on.
(function () {
  "use strict";

  // Copy buttons. The value comes from the button's own data attribute, so nothing is scraped out
  // of the document and nothing is held after the click.
  document.addEventListener("click", function (event) {
    var button = event.target.closest("button.copy");
    if (!button) return;
    var value = button.getAttribute("data-copy") || "";
    var said = button.textContent;
    var done = function (ok) {
      button.textContent = ok ? "Copied" : "Press ⌘/Ctrl+C";
      setTimeout(function () { button.textContent = said; }, 1500);
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(value).then(function () { done(true); },
                                                function () { done(false); });
      return;
    }
    // Plain HTTP on loopback, which is how a development gateway is reached: there is no clipboard
    // API there, so select the value and let the person press the key themselves.
    var holder = button.previousElementSibling;
    if (holder && window.getSelection) {
      var range = document.createRange();
      range.selectNodeContents(holder);
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
    done(false);
  });

  // One deliberate pause in front of an action that cannot be undone. The server asks for the
  // publisher's own ID as well; this is only so a mis-aimed click is not the whole story.
  document.addEventListener("submit", function (event) {
    var button = event.submitter;
    var question = button && button.getAttribute("data-confirm");
    if (question && !window.confirm(question)) {
      event.preventDefault();
    }
  });
})();
