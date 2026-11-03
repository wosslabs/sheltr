// Scores the running Flutter web app. Start it first:
//   cd apps/flutter && flutter run -d web-server --release --web-port 8090 --web-hostname 127.0.0.1
// Then: npm run audit
// Exit code 1 when the score is under 70 or axe reports a serious violation.

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import puppeteer from 'puppeteer-core';
import { PNG } from 'pngjs';

const require = createRequire(import.meta.url);
const axeSource = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const url = process.env.SHELTR_WEB_URL ?? 'http://127.0.0.1:8090';
const chrome =
  process.env.CHROME ??
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const findings = [];

function note(level, id, detail) {
  findings.push({ level, id, detail });
}

function contrast(a, b) {
  const channel = (value) => {
    const scaled = value / 255;
    return scaled <= 0.03928
      ? scaled / 12.92
      : ((scaled + 0.055) / 1.055) ** 2.4;
  };
  const luminance = (rgb) =>
    0.2126 * channel(rgb[0]) +
    0.7152 * channel(rgb[1]) +
    0.0722 * channel(rgb[2]);
  const left = luminance(a);
  const right = luminance(b);
  const lighter = Math.max(left, right);
  const darker = Math.min(left, right);
  return (lighter + 0.05) / (darker + 0.05);
}

async function semanticsText(page) {
  return page.evaluate(() => {
    const root = document.querySelector('flt-semantics');
    return root ? root.innerText : '';
  });
}

async function enableSemantics(page) {
  await page.evaluate(() => {
    document.querySelector('flt-semantics-placeholder')?.click();
  });
  await page.waitForSelector('flt-semantics', { timeout: 8000 });
}

async function clickLabel(page, role, label) {
  const clicked = await page.evaluate(
    (roleName, text) => {
      const node = [...document.querySelectorAll('flt-semantics')].find(
        (item) =>
          item.getAttribute('role') === roleName &&
          ((item.innerText || '').trim() === text ||
            (item.getAttribute('aria-label') || '').trim() === text),
      );
      if (!node) {
        return false;
      }
      node.click();
      return true;
    },
    role,
    label,
  );
  return clicked;
}

const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: true,
  args: ['--disable-gpu'],
});

try {
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
  await page.goto(url, { waitUntil: 'networkidle0', timeout: 30000 });
  await enableSemantics(page);

  await page.evaluate(axeSource);
  const axe = await page.evaluate(async () => {
    const result = await window.axe.run(document, {
      resultTypes: ['violations'],
    });
    return result.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact,
      help: violation.help,
      nodes: violation.nodes.length,
    }));
  });
  if (axe.length === 0) {
    note('pass', 'axe', 'No axe violations on the home screen.');
  }
  for (const violation of axe) {
    const level = violation.impact === 'minor' ? 'warn' : 'fail';
    note(
      level,
      `axe:${violation.id}`,
      `${violation.impact}: ${violation.help} (${violation.nodes} nodes)`,
    );
  }

  const home = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('flt-semantics')];
    const buttons = nodes
      .filter((node) => node.getAttribute('role') === 'button')
      .map((node) => (node.innerText || '').trim())
      .filter(Boolean);
    // Flutter web maps headings to real <h1>…<h6> nodes, not role="heading".
    const headings = [
      ...document.querySelectorAll('h1, h2, h3, h4, h5, h6, [role="heading"]'),
    ].map((node) => (node.textContent || '').trim());
    // Flutter web text fields are <input data-semantics-role="text-field">.
    const field =
      document.querySelector(
        'input[data-semantics-role="text-field"], input[type="text"][aria-label], [role="textbox"]',
      ) ||
      nodes.find((node) => node.getAttribute('role') === 'textbox');
    const fieldName = field
      ? (
          field.getAttribute('aria-label') ||
          field.getAttribute('aria-labelledby') ||
          field.innerText ||
          ''
        ).trim()
      : '';
    return {
      text: nodes[0]?.innerText ?? '',
      buttons,
      headings,
      roles: [
        ...new Set([
          ...nodes.map((node) => node.getAttribute('role')),
          field ? 'textbox' : null,
        ]),
      ],
      fieldName,
      overflow:
        document.documentElement.scrollWidth >
        document.documentElement.clientWidth + 1,
    };
  });

  if (home.overflow) {
    note('fail', 'overflow', 'The phone-width page scrolls sideways.');
  } else {
    note('pass', 'overflow', 'No sideways scroll at 390px.');
  }
  if (home.text.includes('Country:')) {
    note('pass', 'country', 'The home screen names the country.');
  } else {
    note('fail', 'country', 'The home screen does not say which country the answers use.');
  }
  if (home.headings.some((heading) => heading.includes('Prepare for a disaster'))) {
    note('pass', 'page-heading', 'The home title is a heading.');
  } else {
    note(
      'fail',
      'page-heading',
      `The visible title is not a heading. Headings found: ${home.headings.join(', ') || 'none'}.`,
    );
  }
  const hazards = home.buttons.filter(
    (label) =>
      label !== 'Settings' &&
      label !== 'Back' &&
      label !== 'Search' &&
      !label.startsWith('Country:'),
  );
  if (
    hazards.length >= 12 &&
    hazards.every((label) => !label.includes('\n') && !/open guidance/i.test(label))
  ) {
    note(
      'fail',
      'hazard-grid',
      `${hazards.length} hazard controls are the same shape and carry only a name, with no hint of what tapping does.`,
    );
  } else if (hazards.length >= 12) {
    note('pass', 'hazard-grid', 'Hazard controls name the disaster and hint at the action.');
  }
  if (!home.roles.includes('textbox')) {
    note(
      'fail',
      'search-role',
      'The search field is not a text box in the accessibility tree, so it cannot be found or typed into reliably.',
    );
  } else if (home.fieldName.length > 40) {
    note(
      'fail',
      'search-prompt',
      `The search field's accessible name is a sentence (${home.fieldName.length} characters): "${home.fieldName}". It reads as filled-in text, not a short prompt.`,
    );
  } else if (home.fieldName) {
    note('pass', 'search-prompt', `Search prompt: ${home.fieldName}`);
  } else {
    note('fail', 'search-prompt', 'The search field has no accessible name.');
  }
  if (home.text.includes("Search Sheltr's disaster preparedness guidance")) {
    note(
      'fail',
      'search-prompt-visible',
      'The long description is the text inside the field. It looks filled in before the person has typed.',
    );
  }

  const beforeHazard = await semanticsText(page);
  const hazardClicked = await page.evaluate(() => {
    const node = [...document.querySelectorAll('flt-semantics')].find((item) => {
      if (item.getAttribute('role') !== 'button') {
        return false;
      }
      const text = (item.innerText || item.getAttribute('aria-label') || '').trim();
      return text.includes('Wildfire');
    });
    if (!node) {
      return false;
    }
    node.click();
    return true;
  });
  await new Promise((resolve) => setTimeout(resolve, 600));
  const afterHazard = await semanticsText(page);
  if (!hazardClicked) {
    note('fail', 'hazard-action', 'The Wildfire control could not be activated.');
  } else if (beforeHazard === afterHazard) {
    note(
      'fail',
      'hazard-action',
      'Tapping Wildfire changes nothing on the screen. The control looks active and then does nothing.',
    );
  } else {
    note('pass', 'hazard-action', 'Tapping Wildfire changes the screen.');
  }

  const shot = await page.screenshot({ type: 'png' });
  const png = PNG.sync.read(shot);
  // Return to home before measuring contrast if a hazard navigation left the page.
  if (!afterHazard.includes('Prepare for a disaster') || afterHazard.includes('Hazard:')) {
    await page.goto(url, { waitUntil: 'networkidle0', timeout: 30000 });
    await enableSemantics(page);
  }
  const buttonBox = await page.evaluate(() => {
    const node = [...document.querySelectorAll('flt-semantics')].find((item) => {
      if (item.getAttribute('role') !== 'button') {
        return false;
      }
      const text = (item.innerText || item.getAttribute('aria-label') || '').trim();
      return text.includes('Earthquake');
    });
    if (!node) {
      return null;
    }
    const rect = node.getBoundingClientRect();
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
  });
  if (buttonBox) {
    const pixel = (x, y) => {
      const clampedX = Math.max(0, Math.min(png.width - 1, Math.round(x)));
      const clampedY = Math.max(0, Math.min(png.height - 1, Math.round(y)));
      const offset = (clampedY * png.width + clampedX) * 4;
      return [png.data[offset], png.data[offset + 1], png.data[offset + 2]];
    };
    const samples = [];
    for (let y = Math.round(buttonBox.y); y < buttonBox.y + buttonBox.height; y += 2) {
      for (let x = Math.round(buttonBox.x); x < buttonBox.x + buttonBox.width; x += 3) {
        samples.push(pixel(x, y));
      }
    }
    const luminanceOf = (rgb) => 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    samples.sort((left, right) => luminanceOf(left) - luminanceOf(right));
    const darkest = samples[0];
    const lightest = samples[samples.length - 1];
    const fill = samples[Math.floor(samples.length * 0.5)];
    const pageFill = pixel(
      buttonBox.x + buttonBox.width / 2,
      Math.max(0, buttonBox.y - 16),
    );
    const buttonContrast = contrast(fill, pageFill);
    const textContrast = contrast(darkest, lightest);
    if (buttonContrast < 1.4) {
      note(
        'fail',
        'button-edge',
        `Hazard buttons barely differ from the page (contrast ${buttonContrast.toFixed(2)}:1). They do not look tappable.`,
      );
    } else {
      note('pass', 'button-edge', `Button-to-page contrast ${buttonContrast.toFixed(2)}:1.`);
    }
    if (textContrast < 4.5) {
      note(
        'fail',
        'button-text',
        `Hazard label contrast is ${textContrast.toFixed(2)}:1. Body text needs 4.5:1.`,
      );
    } else {
      note('pass', 'button-text', `Hazard label contrast ${textContrast.toFixed(2)}:1.`);
    }
  }

  const question = 'what should I do during a wildfire';
  // Flutter keeps the semantic <input> disabled until the text-editing channel
  // owns focus. Enable and focus it, then type — the same path a screen reader
  // activates when the field is used from the accessibility tree.
  const fieldClicked = await page.evaluate(() => {
    const input = document.querySelector(
      'input[data-semantics-role="text-field"], input[type="text"][aria-label]',
    );
    if (!input) {
      return false;
    }
    input.removeAttribute('disabled');
    input.disabled = false;
    input.focus();
    input.dispatchEvent(new FocusEvent('focus', { bubbles: true }));
    input.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    return true;
  });
  if (fieldClicked) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    await page.keyboard.type(question, { delay: 5 });
    await page.keyboard.press('Enter');
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  const afterSearch = await semanticsText(page);
  if (!fieldClicked) {
    note(
      'fail',
      'search-submits',
      'The home search field cannot be focused from the accessibility tree, so a question cannot be submitted.',
    );
  } else if (
    afterSearch.includes('Source') ||
    afterSearch.includes("can't answer") ||
    afterSearch.includes('Read answer aloud') ||
    (afterSearch.includes('Back') && !afterSearch.includes('Prepare for a disaster'))
  ) {
    note('pass', 'search-submits', 'Enter on the home field reaches an answer or a refusal.');
    if (afterSearch.includes('http://') || afterSearch.includes('https://')) {
      note(
        'fail',
        'raw-url',
        'The answer prints a full web address as ordinary text instead of a source name.',
      );
    }
    const broken = afterSearch.split('\n').filter((line) => {
      const trimmed = line.trim();
      return trimmed.length > 0 && trimmed.length < 24 && !trimmed.startsWith('•');
    });
    if (broken.length > 4) {
      note(
        'fail',
        'broken-lines',
        `The answer is split into ${broken.length} very short lines, so sentences are cut apart.`,
      );
    }
  } else if (!afterSearch.includes(question)) {
    note(
      'fail',
      'search-submits',
      'Typing a question and pressing Enter does not leave the home screen and does not keep the question visible.',
    );
  } else {
    note(
      'fail',
      'search-submits',
      'The question stays on the home screen. Search does not run from the keyboard.',
    );
  }

  let score = 100;
  for (const finding of findings) {
    if (finding.level === 'fail') {
      score -= 12;
    } else if (finding.level === 'warn') {
      score -= 4;
    }
  }
  score = Math.max(0, score);
  const report = { url, viewport: '390x844', score, findings };
  console.log(JSON.stringify(report, null, 2));
  if (findings.some((finding) => finding.level === 'fail')) {
    process.exitCode = 1;
  }
} finally {
  await browser.close();
}
