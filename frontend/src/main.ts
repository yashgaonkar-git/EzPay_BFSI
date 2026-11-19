import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { bootstrapApplication } from '@angular/platform-browser';

type Page = 'Overview' | 'Send money' | 'Transactions' | 'Bills' | 'Notifications' | 'Settings';
type Method = 'UPI' | 'BANK';
type AuthMode = 'login' | 'register' | 'recover' | 'reset' | 'verify';
type Challenge = { challenge: string; code: string };
type Profile = {
  name: string; email: string; mobile: string; address: string; photo: string;
  upi: string; bank: string; balance: number; confirmations: boolean; reminders: boolean;
};
type Payment = {
  id: string; method: Method; amount: number; recipient: string; note: string;
  state: string; failure: string | null; billId: string | null; createdAt: string; updatedAt: string;
};
type PaymentRequest = {
  key: string; method: Method; amount: number; recipient: string; account: string;
  ifsc: string; note: string; billId: string;
};
type Bill = { id: string; name: string; amount: number; dueDate: string; monthly: boolean; upi: string; state: string };
type Notice = { id: string; title: string; message: string; createdAt: string; seen: boolean };

class RequestError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

const icons: Record<string, string> = {
  logo: 'M13 2 4 14h7l-1 8 10-12h-7l1-8Z',
  Overview: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  'Send money': 'M22 2 9 15M22 2l-8 20-5-7-7-5 20-8Z',
  Transactions: 'M4 7h16m-4-4 4 4-4 4M20 17H4m4-4-4 4 4 4',
  Bills: 'M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6m-6 4h6',
  Notifications: 'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-8 12a2 2 0 0 0 4 0',
  Settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm0-5v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4m0-12.8L17 7M7 17l-1.4 1.4',
  bank: 'm2 9 10-6 10 6H2Zm3 3v6m5-6v6m4-6v6m5-6v6M3 21h18',
  search: 'M11 5a6 6 0 1 0 0 12 6 6 0 0 0 0-12Zm5 11 4 4',
  lock: 'M6 11h12v9H6v-9Zm2 0V7a4 4 0 0 1 8 0v4m-4 4v2',
  wallet: 'M20 8V5H4a2 2 0 0 0 0 4h16a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7m20 5h-6a2 2 0 0 0 0 4h6',
  chevron: 'm9 6 6 6-6 6',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  plus: 'M12 5v14M5 12h14',
  check: 'm5 12 4 4L19 6',
  logOut: 'M10 4H4v16h6m-1-8h11m-4-4 4 4-4 4',
  menu: 'M4 6h16M4 12h16M4 18h16',
  close: 'm6 6 12 12M18 6 6 18',
  printer: 'M6 9V3h12v6M6 18H3V9h18v9h-3M6 14h12v7H6z',
  help: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0ZM9 9a3 3 0 1 1 4 2.8c-1 .4-1 1.2-1 2.2m0 3h.01'
};

@Component({
  selector: 'ez-icon',
  standalone: true,
  inputs: ['name'],
  template: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="paths[name]"></path></svg>'
})
class Icon {
  name = '';
  paths = icons;
}

const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' });
const calendarDate = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeZone: 'UTC' });

function paise(value: string | number | null): number {
  const text = String(value ?? '').trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(text)) throw new Error('Enter an amount with up to two decimal places.');
  const [whole, decimal = ''] = text.split('.');
  const result = Number(whole) * 100 + Number(decimal.padEnd(2, '0'));
  if (result <= 0) throw new Error('Enter an amount greater than zero.');
  return result;
}

async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, {
    method,
    headers: body === undefined ? { 'X-EzPay-Request': '1' } :
      { 'X-EzPay-Request': '1', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new RequestError(response.status, problem.message || 'The service could not complete the request.');
  }
  return response.status === 204 ? undefined as T : response.json();
}

@Component({
  selector: 'ezpay-app',
  standalone: true,
  imports: [CommonModule, FormsModule, Icon],
  templateUrl: './app.html'
})
class App implements OnInit, OnDestroy {
  @ViewChild('receiptDialog') receiptDialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('searchDialog') searchDialog?: ElementRef<HTMLDialogElement>;
  readonly mainPages: Page[] = ['Overview', 'Send money', 'Transactions', 'Bills'];
  readonly accountPages: Page[] = ['Notifications', 'Settings'];
  readonly pageDescriptions: Record<Page, string> = {
    Overview: 'Manage your payments, bills and recent activity in one place.',
    'Send money': 'Send a demo payment using UPI or a verified demo bank account.',
    Transactions: 'Search your payment history and follow the latest status.',
    Bills: 'Track upcoming bills and manage recurring payments.',
    Notifications: 'Review your payment confirmations, reminders and security alerts.',
    Settings: 'Manage your personal details, payment methods and preferences.'
  };
  page: Page = 'Overview';
  user: Profile | null = null;
  profile: Omit<Profile, 'email' | 'mobile' | 'upi' | 'bank' | 'balance'> | null = null;
  methods = { upi: '', account: '', ifsc: '' };
  activity: Payment[] = [];
  filtered: Payment[] = [];
  bills: Bill[] = [];
  notices: Notice[] = [];
  filters = { q: '', method: '', state: '', from: '', to: '' };
  activeFilterQuery = '';
  auth: AuthMode = 'login';
  authForm = { name: '', email: '', mobile: '', identity: '', password: '', confirmation: '', code: '' };
  challenge: Challenge | null = null;
  verifyPurpose: 'register' | 'login' = 'login';
  resetUrl = '';
  paymentForm = this.blankPayment();
  paymentStage: 'edit' | 'review' | 'verify' = 'edit';
  paymentRequest: PaymentRequest | null = null;
  paymentVerification: Challenge | null = null;
  paymentOtp = '';
  settledPayment: Payment | null = null;
  billForm = this.blankBill();
  billEditor = false;
  billFilter = 'Open';
  selected: Payment | null = null;
  hiddenBalance = false;
  search = '';
  mobileOpen = false;
  busy = false;
  loading = true;
  error = '';
  success = '';
  browserPermission = typeof Notification === 'undefined' ? 'unsupported' : Notification.permission;
  private deliverySeen = new Set<string>();
  private inboxInitialized = false;
  private refreshTimer = 0;
  private refreshRunning = false;
  private refreshSequence = 0;
  private generation = 0;

  constructor(private readonly changes: ChangeDetectorRef) {}

  async ngOnInit() {
    window.addEventListener('hashchange', this.onLocation);
    const resetLocation = new URLSearchParams(location.hash.startsWith('#reset?') ? location.hash.slice(7) : '');
    if (resetLocation.has('challenge') && resetLocation.has('code')) {
      this.auth = 'reset';
      this.challenge = { challenge: resetLocation.get('challenge')!, code: resetLocation.get('code')! };
      history.replaceState(null, '', location.pathname);
    } else {
      try {
        const profile = await api<Profile>('/profile');
        this.enter(profile);
        await this.refresh(true);
      } catch (error) {
        if (!(error instanceof RequestError && error.status === 401)) this.error = this.message(error);
      }
    }
    this.loading = false;
    this.changes.markForCheck();
    this.refreshTimer = window.setInterval(() => void this.refresh(), 1500);
  }

  ngOnDestroy() {
    clearInterval(this.refreshTimer);
    window.removeEventListener('hashchange', this.onLocation);
  }

  private onLocation = () => {
    if (!this.user) return;
    const next = [...this.mainPages, ...this.accountPages].find(page => this.slug(page) === location.hash.slice(1));
    if (next && next !== this.page) {
      this.transition(next);
      this.changes.markForCheck();
      void this.refresh(true);
    }
  };

  private slug(page: Page) { return page.toLowerCase().replaceAll(' ', '-'); }

  navigate(page: Page) {
    this.transition(page);
    if (location.hash !== '#' + this.slug(page)) location.hash = this.slug(page);
    this.searchDialog?.nativeElement.close();
    void this.refresh(true);
  }

  private transition(page: Page) {
    this.page = page;
    this.mobileOpen = false;
    this.error = '';
    this.success = '';
    window.scrollTo(0, 0);
  }

  showAuth(mode: AuthMode) {
    this.auth = mode;
    this.error = '';
    this.success = '';
    this.authForm.password = '';
    this.authForm.confirmation = '';
    this.authForm.code = '';
    this.challenge = null;
    this.resetUrl = '';
  }

  submitAuth() {
    return this.perform(async () => {
      const { name, email, mobile, identity, password, confirmation, code } = this.authForm;
      if (this.auth === 'register') {
        this.challenge = await api('/auth/register', 'POST', { name, email, mobile, password });
        this.verifyPurpose = 'register';
        this.auth = 'verify';
      } else if (this.auth === 'login') {
        this.challenge = await api('/auth/login', 'POST', { identity, password });
        this.verifyPurpose = 'login';
        this.auth = 'verify';
      } else if (this.auth === 'verify' && this.challenge) {
        await api('/auth/' + this.verifyPurpose + '/verify', 'POST', { challenge: this.challenge.challenge, code });
        if (this.verifyPurpose === 'register') {
          this.authForm.identity = email;
          this.showAuth('login');
          this.success = 'Account created. Sign in to get started.';
        } else {
          this.enter(await api('/profile'));
          await this.refresh(true);
        }
      } else if (this.auth === 'recover') {
        this.challenge = await api('/auth/recover', 'POST', { identity });
        const pair = this.challenge!;
        this.resetUrl = location.origin + location.pathname +
          '#reset?' + new URLSearchParams({ challenge: pair.challenge, code: pair.code });
      } else if (this.auth === 'reset' && this.challenge) {
        await api('/auth/reset', 'POST', { ...this.challenge, password, confirmation });
        this.showAuth('login');
        this.success = 'Password updated. Sign in with your new password.';
      }
    });
  }

  resetHere(event: Event) {
    event.preventDefault();
    this.auth = 'reset';
    this.authForm.password = '';
    this.authForm.confirmation = '';
  }

  openDemo() {
    return this.perform(async () => {
      const stamp = crypto.randomUUID();
      const mobile = '+919' + String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000_000).padStart(9, '0');
      const email = 'demo-' + stamp + '@example.test';
      const password = 'Sandbox-' + stamp;
      const registration = await api<Challenge>('/auth/register', 'POST', { name: 'Demo User', email, mobile, password });
      await api('/auth/register/verify', 'POST', registration);
      const login = await api<Challenge>('/auth/login', 'POST', { identity: email, password });
      await api('/auth/login/verify', 'POST', login);
      this.enter(await api('/profile/methods', 'POST', { upi: 'mywallet@demo', account: '123456789012', ifsc: 'DEMO0000001' }));
      await this.refresh(true);
    });
  }

  private enter(profile: Profile) {
    this.generation++;
    this.user = profile;
    this.hiddenBalance = false;
    this.deliverySeen.clear();
    this.inboxInitialized = false;
    this.authForm.password = '';
    this.authForm.confirmation = '';
    this.challenge = null;
    this.activity = [];
    this.filtered = [];
    this.bills = [];
    this.notices = [];
    this.filters = { q: '', method: '', state: '', from: '', to: '' };
    this.activeFilterQuery = '';
    this.methods = { upi: profile.upi, account: '', ifsc: '' };
    this.blankProfile(profile);
    const initial = [...this.mainPages, ...this.accountPages].find(page => this.slug(page) === location.hash.slice(1));
    this.transition(initial ?? 'Overview');
  }

  signOut() {
    return this.perform(async () => {
      await api('/auth/logout', 'POST');
      this.leave();
    });
  }

  private leave() {
    this.generation++;
    this.user = null;
    this.profile = null;
    this.selected = null;
    this.activity = [];
    this.filtered = [];
    this.bills = [];
    this.notices = [];
    this.search = '';
    this.billForm = this.blankBill();
    this.billEditor = false;
    this.billFilter = 'Open';
    this.authForm = { name: '', email: '', mobile: '', identity: '', password: '', confirmation: '', code: '' };
    this.clearPayment();
    history.replaceState(null, '', location.pathname);
    this.showAuth('login');
  }

  refreshReport() {
    return this.perform(async () => {
      const query = new URLSearchParams(Object.entries(this.filters).filter(([, value]) => value)).toString();
      this.filtered = await api('/payments?' + query);
      this.activeFilterQuery = query;
    });
  }

  clearFilters() {
    this.filters = { q: '', method: '', state: '', from: '', to: '' };
    void this.refreshReport();
  }

  printReport() { window.print(); }

  startPayment(method: Method, bill?: Bill) {
    this.clearPayment();
    this.paymentForm.method = method;
    if (bill) {
      this.paymentForm.amount = (bill.amount / 100).toFixed(2);
      this.paymentForm.recipient = bill.upi;
      this.paymentForm.note = bill.name;
      this.paymentForm.billId = bill.id;
    }
    this.navigate('Send money');
  }

  reviewPayment() {
    return this.perform(async () => {
      const { amount, ...details } = this.paymentForm;
      if (details.method === 'UPI' && !this.user?.upi) throw new Error('Link your demo UPI ID in Settings first.');
      if (details.method === 'BANK' && !this.user?.bank) throw new Error('Link your demo bank account in Settings first.');
      this.paymentRequest = { ...details, amount: paise(amount), key: crypto.randomUUID() };
      this.paymentStage = 'review';
    });
  }

  confirmPayment() {
    return this.perform(async () => {
      if (!this.paymentRequest) return;
      const request = this.paymentVerification ? {
        ...this.paymentRequest, challenge: this.paymentVerification.challenge, code: this.paymentOtp
      } : this.paymentRequest;
      const result = await api<{ payment: Payment | null; verification: Challenge | null }>('/payments', 'POST', request);
      if (result.verification) {
        this.paymentVerification = result.verification;
        this.paymentStage = 'verify';
      } else if (result.payment) {
        this.clearPayment();
        this.settledPayment = result.payment;
        await this.refresh(true);
      }
    });
  }

  editPayment() {
    this.error = '';
    this.paymentStage = 'edit';
    this.paymentRequest = null;
    this.paymentVerification = null;
    this.paymentOtp = '';
  }

  clearPayment() {
    this.editPayment();
    this.settledPayment = null;
    this.paymentForm = this.blankPayment();
  }

  private blankPayment() {
    return { method: 'UPI' as Method, amount: '' as string | number, recipient: '', account: '', ifsc: '', note: '', billId: '' };
  }

  openReceipt(payment: Payment) {
    this.selected = payment;
    this.error = '';
    this.receiptDialog?.nativeElement.showModal();
    void this.refresh(true);
  }

  closeReceipt() { this.selected = null; }

  addBill() {
    return this.perform(async () => {
      const { amount, ...details } = this.billForm;
      await api('/bills', 'POST', { ...details, amount: paise(amount) });
      this.billForm = this.blankBill();
      this.billEditor = false;
      this.billFilter = 'Open';
      this.success = 'Bill added successfully.';
      await this.refresh(true);
    });
  }

  private blankBill() { return { name: '', amount: '' as string | number, dueDate: '', upi: '', monthly: false }; }

  saveProfile() {
    return this.perform(async () => {
      this.user = await api('/profile', 'PUT', this.profile);
      this.success = 'Profile and preferences saved.';
    });
  }

  linkMethods() {
    return this.perform(async () => {
      this.user = await api<Profile>('/profile/methods', 'POST', this.methods);
      this.methods = { upi: this.user.upi, account: '', ifsc: '' };
      this.success = 'Demo payment methods updated.';
    });
  }

  private blankProfile(user: Profile) {
    const { name, address, photo, confirmations, reminders } = user;
    this.profile = { name, address, photo, confirmations, reminders };
  }

  selectPhoto(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.error = '';
    if (file.size > 330_000 || !['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
      this.error = 'Choose a PNG, JPEG or WebP photo under 330 KB.';
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (this.profile && typeof reader.result === 'string') this.profile.photo = reader.result;
      this.changes.markForCheck();
    };
    reader.readAsDataURL(file);
  }

  markRead(notice: Notice) {
    return this.perform(async () => {
      await api('/notifications/' + notice.id + '/read', 'POST');
      notice.seen = true;
      await this.refresh(true);
    });
  }

  enableBrowserNotifications() {
    return this.perform(async () => {
      if (typeof Notification === 'undefined') throw new Error('This browser does not support desktop notifications.');
      this.browserPermission = await Notification.requestPermission();
      if (this.browserPermission !== 'granted') throw new Error('Desktop notifications are disabled in your browser settings.');
      this.success = 'Desktop notifications enabled while EzPay is open.';
    });
  }

  openSearch() {
    this.search = '';
    this.searchDialog?.nativeElement.showModal();
  }

  get matchingPages() {
    const term = this.search.trim().toLowerCase();
    return [...this.mainPages, ...this.accountPages].filter(page => (page + ' ' + this.pageDescriptions[page]).toLowerCase().includes(term));
  }

  get visibleBills() {
    return this.bills.filter(bill => {
      if (this.billFilter === 'All') return true;
      if (this.billFilter === 'Open') return bill.state !== 'PAID';
      return bill.state === this.billFilter.toUpperCase();
    });
  }

  get unread() { return this.notices.filter(notice => !notice.seen).length; }
  get upcoming() { return this.bills.filter(bill => bill.state === 'UPCOMING' || bill.state === 'OVERDUE'); }
  get firstName() { return this.user?.name.split(/\s+/)[0] ?? ''; }
  get initials() { return this.user?.name.split(/\s+/).slice(0, 2).map(word => word[0]).join('').toUpperCase() ?? ''; }
  value(amount: number) { return money.format(amount / 100); }
  date(value: string) { return dateTime.format(new Date(value)); }
  billDate(value: string) { return calendarDate.format(new Date(value + 'T00:00:00Z')); }
  methodName(method: Method) { return method === 'UPI' ? 'UPI payment' : 'Bank transfer'; }
  stateName(value: string) { return value.charAt(0) + value.slice(1).toLowerCase(); }

  private async refresh(initial = false) {
    if (!this.user || (this.refreshRunning && !initial)) return;
    const generation = this.generation;
    const sequence = ++this.refreshSequence;
    const transactionQuery = this.activeFilterQuery;
    const track = this.selected?.id ?? this.settledPayment?.id;
    const page = this.page;
    this.refreshRunning = true;
    try {
      const [profile, activity, bills, notices, filtered, receipt] = await Promise.all([
        api<Profile>('/profile'), api<Payment[]>('/payments'), api<Bill[]>('/bills'),
        api<Notice[]>('/notifications'),
        page === 'Transactions' && transactionQuery ? api<Payment[]>('/payments?' + transactionQuery) : Promise.resolve(null),
        track ? api<Payment>('/payments/' + track) : Promise.resolve(null)
      ]);
      if (generation !== this.generation || sequence !== this.refreshSequence) return;
      this.user = profile;
      this.activity = activity;
      if (transactionQuery === this.activeFilterQuery && this.page === page) this.filtered = filtered ?? activity;
      this.bills = bills;
      this.notices = notices;
      if (this.selected && receipt?.id === this.selected.id) this.selected = receipt;
      if (this.settledPayment && receipt?.id === this.settledPayment.id) this.settledPayment = receipt;
      for (const notice of notices) {
        if (this.inboxInitialized && !notice.seen && !this.deliverySeen.has(notice.id) && this.browserPermission === 'granted') {
          try { new Notification('EzPay · ' + notice.title, { tag: notice.id }); } catch { /* Inbox remains available. */ }
        }
        this.deliverySeen.add(notice.id);
      }
      this.inboxInitialized = true;
    } catch (error) {
      if (generation !== this.generation || sequence !== this.refreshSequence) return;
      if (error instanceof RequestError && error.status === 401) this.leave();
      this.error = this.message(error);
    } finally {
      if (sequence === this.refreshSequence) {
        this.refreshRunning = false;
        this.changes.markForCheck();
      }
    }
  }

  private async perform(operation: () => Promise<void>) {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.success = '';
    try { await operation(); }
    catch (error) { this.error = this.message(error); }
    finally {
      this.busy = false;
      this.changes.markForCheck();
    }
  }

  private message(error: unknown) { return error instanceof Error ? error.message : 'Something went wrong. Try again.'; }
}

bootstrapApplication(App).catch(error => console.error(error));
