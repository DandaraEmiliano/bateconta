const $ = (id) => document.getElementById(id);
const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const signedBrl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', signDisplay: 'exceptZero' });
const format = (cents, formatter = brl) => cents == null ? '—' : formatter.format(cents / 100);
const amount = (number, singular, plural) => number + ' ' + (number === 1 ? singular : plural);
const statuses = {
  MATCHED: { label: 'Conciliadas', badge: 'Conciliada', tone: 'green', icon: '✓', note: 'O valor repassado bate com o valor bruto da venda menos a taxa.' },
  AMOUNT_MISMATCH: { label: 'Valor divergente', badge: 'Valor divergente', tone: 'amber', icon: '≠', note: 'O valor recebido difere do líquido esperado. A diferença é o valor recebido menos o esperado.' },
  MISSING_PAYOUT: { label: 'Repasse ausente', badge: 'Repasse ausente', tone: 'red', icon: '−', note: 'A venda está no arquivo da loja. O relatório da operadora não contém um repasse para esse ID.' },
  UNEXPECTED_PAYOUT: { label: 'Venda ausente', badge: 'Venda ausente', tone: 'blue', icon: '+', note: 'O relatório contém um repasse para este ID. O arquivo da loja não contém a venda correspondente.' },
  DUPLICATE: { label: 'IDs duplicados', badge: 'ID duplicado', tone: 'purple', icon: '⧉', note: 'Mais de um registro usa esse ID em um dos arquivos. Confira as linhas de origem antes de conciliar.' }
};
const pageSize = 10;
const maxFileSize = 1024 * 1024;
const inputFiles = new Map();
let report;
let page = 0;
let busy = false;

function create(tag, text, className) {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

for (const [status, info] of Object.entries(statuses)) {
  const option = create('option', info.label);
  option.value = status;
  $('filter').append(option);
}

function notice(text = '', error = false) {
  const element = $('message');
  element.textContent = text;
  element.classList.toggle('error', error);
  element.hidden = text.length === 0;
}

function chooseFile(input, file) {
  const display = $(input.name + '-file');
  const card = input.closest('.file-card');
  if (!file) return;
  if (!/\.csv$/i.test(file.name) || file.size > maxFileSize) {
    input.value = '';
    inputFiles.delete(input.name);
    display.textContent = 'Escolha ou arraste seu CSV';
    card.classList.remove('has-file');
    notice('Selecione um arquivo .csv de até 1 MB.', true);
    return;
  }
  inputFiles.set(input.name, file);
  display.textContent = file.name;
  card.classList.add('has-file');
  notice();
}

for (const field of document.querySelectorAll('input[type=file]')) {
  const drop = field.closest('.drop-target');
  field.addEventListener('change', () => chooseFile(field, field.files[0]));
  drop.addEventListener('dragover', (event) => {
    event.preventDefault();
    if (!busy) drop.classList.add('drag-over');
  });
  drop.addEventListener('dragleave', () => drop.classList.remove('drag-over'));
  drop.addEventListener('drop', (event) => {
    event.preventDefault();
    drop.classList.remove('drag-over');
    if (!busy) {
      field.value = '';
      chooseFile(field, event.dataTransfer.files[0]);
    }
  });
}

async function run(useExample) {
  if (busy) return;
  if (!useExample && inputFiles.size !== 2) {
    notice('Selecione os dois arquivos CSV para começar.', true);
    document.querySelector(inputFiles.has('vendas') ? '[name=repasses]' : '[name=vendas]').focus();
    return;
  }

  busy = true;
  const controls = [$('run'), $('sample'), ...document.querySelectorAll('input[type=file]')];
  for (const element of controls) element.disabled = true;
  const action = useExample ? $('sample') : $('run');
  const label = action.textContent;
  action.textContent = 'Conciliando…';
  notice('Processando os arquivos…');

  const payload = new FormData();
  for (const [part, file] of inputFiles) payload.append(part, file);
  try {
    const response = await fetch(useExample ? '/api/exemplo' : '/api/conciliacoes', useExample ? {
      headers: { Accept: 'application/json' }
    } : { method: 'POST', headers: { Accept: 'application/json' }, body: payload });
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(response.status >= 500 ? 'Serviço indisponível. Tente novamente.' :
        data.detail || 'Confira o formato dos arquivos e tente novamente.');
    }
    report = await response.json();
    showReport(useExample);
    notice();
  } catch (error) {
    notice(error instanceof TypeError ? 'Não foi possível conectar. Tente novamente.' : error.message, true);
  } finally {
    busy = false;
    action.textContent = label;
    for (const element of controls) element.disabled = false;
  }
}

function showReport(useExample) {
  page = 0;
  $('search').value = '';
  $('filter').value = 'ALL';
  $('result-source').textContent = useExample ? 'Dados de exemplo' : 'Arquivos importados';
  $('result-meta').textContent = amount(report.saleCount, 'venda', 'vendas') + ' · ' +
    amount(report.payoutCount, 'repasse', 'repasses');
  $('stats').replaceChildren(...Object.entries(statuses).map(([status, info]) => {
    const card = create('button', null, 'stat stat-' + info.tone);
    card.type = 'button';
    card.dataset.status = status;
    const icon = create('span', info.icon, 'stat-icon');
    icon.setAttribute('aria-hidden', 'true');
    const content = create('span', null, 'stat-content');
    content.append(create('strong', report.counts[status]), create('small', info.label));
    card.append(icon, content);
    card.addEventListener('click', () => {
      $('filter').value = $('filter').value === status ? 'ALL' : status;
      resetPage();
    });
    return card;
  }));
  $('resultados').hidden = false;
  $('results-link').hidden = false;
  renderRows();
  $('result-focus').focus();
}

function renderRows() {
  const filter = $('filter').value;
  const query = $('search').value.trim().toUpperCase();
  const filtered = report.items.filter(item =>
    (filter === 'ALL' || item.status === filter) && item.id.toUpperCase().includes(query)
  );

  for (const card of $('stats').children) {
    card.setAttribute('aria-pressed', String(card.dataset.status === filter));
  }
  $('filtered-count').textContent = amount(filtered.length, 'transação', 'transações');
  $('no-results').hidden = filtered.length > 0;
  $('no-results').textContent = report.items.length === 0
    ? 'Os arquivos não contêm transações.'
    : 'Nenhuma transação corresponde aos filtros.';
  $('rows').replaceChildren(...filtered.slice(page * pageSize, (page + 1) * pageSize).map(renderRow));
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  $('pagination').hidden = totalPages <= 1;
  $('prev').disabled = page === 0;
  $('next').disabled = page + 1 >= totalPages;
  $('page-info').textContent = 'Página ' + (page + 1) + ' de ' + totalPages;
}

function renderRow(item) {
  const info = statuses[item.status];
  const row = create('tr');
  const title = create('th', null, 'transaction');
  title.scope = 'row';
  const idButton = create('button', item.id, 'text-button');
  idButton.type = 'button';
  idButton.addEventListener('click', () => showDetail(item));
  title.append(idButton);
  const status = create('td');
  status.append(create('span', info.badge, 'badge ' + info.tone));
  const actions = create('td');
  const details = create('button', 'Detalhes', 'text-button');
  details.type = 'button';
  details.setAttribute('aria-label', 'Detalhes de ' + item.id);
  details.addEventListener('click', () => showDetail(item));
  actions.append(details);
  row.append(title, status, create('td', format(item.expectedCents)), create('td', format(item.actualCents)),
    create('td', format(item.differenceCents, signedBrl), item.differenceCents ? 'difference' : ''), actions);
  return row;
}

function recordList(element, records, formatRecord) {
  const items = records.slice(0, 20).map((record) => create('li', formatRecord(record)));
  if (records.length > 20) items.push(create('li', 'Mais ' + (records.length - 20) + ' registros'));
  if (!records.length) items.push(create('li', 'Nenhum registro', 'muted'));
  element.replaceChildren(...items);
}

function showDetail(item) {
  const info = statuses[item.status];
  $('detail-title').textContent = item.id;
  $('detail-status').textContent = info.badge;
  $('detail-status').className = 'badge ' + info.tone;
  $('detail-explain').textContent = info.note;
  $('detail-expected').textContent = format(item.expectedCents);
  $('detail-actual').textContent = format(item.actualCents);
  $('detail-difference').textContent = format(item.differenceCents, signedBrl);
  recordList($('detail-sales'), item.sales, sale => 'Bruto ' + format(sale.grossCents) + ' · Taxa ' + format(sale.feeCents));
  recordList($('detail-payouts'), item.payouts, cents => 'Repasse ' + format(cents));
  $('detail').showModal();
}

function resetPage() {
  page = 0;
  renderRows();
}
$('sample').addEventListener('click', () => { void run(true); });
$('import-form').addEventListener('submit', (event) => {
  event.preventDefault();
  void run(false);
});
$('search').addEventListener('input', resetPage);
$('filter').addEventListener('change', resetPage);
$('prev').addEventListener('click', () => { page--; renderRows(); });
$('next').addEventListener('click', () => { page++; renderRows(); });
$('close').addEventListener('click', () => $('detail').close());
