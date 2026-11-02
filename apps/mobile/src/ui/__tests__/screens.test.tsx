import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import * as Speech from 'expo-speech';
import AboutScreen from '../../app/about';
import AnswerScreen from '../../app/answer';
import HomeScreen from '../../app/index';
import SearchScreen from '../../app/search';
import SettingsScreen, { syncMessageText } from '../../app/settings';
import { translate } from '../../i18n';
import { DOCUMENT, FakeStore, renderWithServices } from './testServices';

const mockPush = jest.fn();
let mockParams: Record<string, string> = {};

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
  useLocalSearchParams: () => mockParams,
  Stack: { Screen: () => null },
}));
jest.mock('expo-speech', () => ({
  speak: jest.fn(),
  stop: jest.fn().mockResolvedValue(undefined),
  getAvailableVoicesAsync: jest.fn().mockResolvedValue([{ language: 'en-US' }]),
}));
jest.mock('expo-linking', () => ({ openURL: jest.fn().mockResolvedValue(true) }));

const en = (key: Parameters<typeof translate>[0], values?: Record<string, string | number>) => translate(key, 'en', values);

beforeEach(() => {
  mockPush.mockClear();
  mockParams = {};
  (Speech.speak as jest.Mock).mockClear();
});

describe('Home', () => {
  it('shows all twelve hazards', () => {
    renderWithServices(<HomeScreen />);
    expect(screen.getAllByTestId(/^hazard-tile-/)).toHaveLength(12);
    expect(screen.getByText(en('hazard_earthquake'))).toBeTruthy();
    expect(screen.getByText(en('hazard_glacial_lake_flood'))).toBeTruthy();
  });

  it('opens Search for a tapped hazard', () => {
    renderWithServices(<HomeScreen />);
    fireEvent.press(screen.getByTestId('hazard-tile-FLOOD'));
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/search', params: { hazard: 'FLOOD' } });
  });

  it('opens Search with trimmed text, and ignores blank text', () => {
    renderWithServices(<HomeScreen />);
    const input = screen.getByTestId('home-search-input');
    fireEvent.changeText(input, '   ');
    fireEvent(input, 'submitEditing');
    expect(mockPush).not.toHaveBeenCalled();
    fireEvent.changeText(input, '  flood help  ');
    fireEvent(input, 'submitEditing');
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/search', params: { query: 'flood help' } });
  });

  it('shows the interface in the chosen language', () => {
    renderWithServices(<HomeScreen />, { settings: { language: 'es' } });
    expect(screen.getByText(translate('home_title', 'es'))).toBeTruthy();
    expect(screen.getByText(translate('hazard_flood', 'es'))).toBeTruthy();
  });
});

describe('Search', () => {
  it('prefills the query and hazard from the route and can clear the hazard', () => {
    mockParams = { query: 'where to go', hazard: 'FLOOD' };
    renderWithServices(<SearchScreen />);
    expect(screen.getByTestId('search-input').props.value).toBe('where to go');
    expect(screen.getByTestId('search-hazard-chip')).toBeTruthy();
    fireEvent.press(screen.getByTestId('search-hazard-chip'));
    expect(screen.queryByTestId('search-hazard-chip')).toBeNull();
  });

  it('ignores an unknown hazard in the route', () => {
    mockParams = { hazard: 'NOT_A_HAZARD' };
    renderWithServices(<SearchScreen />);
    expect(screen.queryByTestId('search-hazard-chip')).toBeNull();
  });

  it('keeps submit disabled for blank text and navigates to Answer with the trimmed text and hazard', () => {
    mockParams = { hazard: 'FLOOD' };
    renderWithServices(<SearchScreen />);
    expect(screen.getByTestId('search-submit').props.accessibilityState.disabled).toBe(true);
    fireEvent.press(screen.getByTestId('search-submit'));
    expect(mockPush).not.toHaveBeenCalled();

    fireEvent.changeText(screen.getByTestId('search-input'), '  flood water ');
    expect(screen.getByTestId('search-submit').props.accessibilityState.disabled).toBe(false);
    fireEvent.press(screen.getByTestId('search-submit'));
    expect(mockPush).toHaveBeenCalledWith({ pathname: '/answer', params: { text: 'flood water', hazard: 'FLOOD' } });
  });
});

describe('Answer', () => {
  it('shows the passage with its source', async () => {
    mockParams = { text: 'flood water' };
    renderWithServices(<AnswerScreen />);
    expect(await screen.findByTestId('answer-screen')).toBeTruthy();
    expect(screen.getByText(/Move to higher ground/)).toBeTruthy();
    expect(screen.getByText('Prepare')).toBeTruthy();
    expect(screen.getByText('UK Government')).toBeTruthy();
    expect(screen.getByText(en('answer_source_published', { p1: '2024-02-02' }))).toBeTruthy();
    expect(screen.getByText(en('answer_source_page', { p1: 3 }))).toBeTruthy();
    expect(screen.getByText('https://prepare.campaign.gov.uk/')).toBeTruthy();
  });

  it('shows Sheltr’s own refusal, not the retriever’s English text', async () => {
    mockParams = { text: 'best pizza recipe' };
    renderWithServices(<AnswerScreen />, { settings: { language: 'fr' } });
    expect(await screen.findByText(translate('answer_refusal_title', 'fr'))).toBeTruthy();
    expect(screen.getByText(translate('answer_refusal_body', 'fr'))).toBeTruthy();
    expect(screen.queryByTestId('answer-read-aloud-button')).toBeNull();
  });

  it('refuses when the route has no question', () => {
    mockParams = {};
    renderWithServices(<AnswerScreen />);
    expect(screen.getByTestId('answer-refusal')).toBeTruthy();
  });

  it('reads the top passage aloud in English whatever the display language', async () => {
    mockParams = { text: 'flood water' };
    renderWithServices(<AnswerScreen />, { settings: { language: 'es' } });
    const button = await screen.findByTestId('answer-read-aloud-button');
    await waitFor(() => expect(button.props.accessibilityState.disabled).toBe(false));
    fireEvent.press(button);
    expect(Speech.speak).toHaveBeenCalledWith(
      expect.stringContaining('Move to higher ground'),
      expect.objectContaining({ language: 'en-US' }),
    );
  });

  it('disables read-aloud and explains why when there is no English voice', async () => {
    (Speech.getAvailableVoicesAsync as jest.Mock).mockResolvedValueOnce([{ language: 'ja-JP' }]);
    mockParams = { text: 'flood water' };
    renderWithServices(<AnswerScreen />);
    expect(await screen.findByText(en('answer_read_aloud_unavailable'))).toBeTruthy();
    expect(screen.getByTestId('answer-read-aloud-button').props.accessibilityState.disabled).toBe(true);
  });

  it('boosts the chosen country and hazard without changing which passages answer', async () => {
    mockParams = { text: 'flood water', hazard: 'FLOOD' };
    renderWithServices(<AnswerScreen />, { settings: { country: 'GB' } });
    expect(await screen.findByText(/Move to higher ground/)).toBeTruthy();
  });
});

describe('Settings', () => {
  it('stores each choice', async () => {
    const { updates } = renderWithServices(<SettingsScreen />);
    fireEvent.press(screen.getByTestId('settings-language-es'));
    fireEvent.press(screen.getByTestId('settings-text-size-LARGE'));
    fireEvent(screen.getByTestId('settings-high-contrast'), 'valueChange', true);
    fireEvent.press(screen.getByTestId('settings-sync-frequency-DAILY'));
    fireEvent(screen.getByTestId('settings-mobile-data'), 'valueChange', true);
    await act(async () => {});
    expect(updates).toEqual([
      { language: 'es' }, { textScale: 'LARGE' }, { highContrast: true },
      { syncFrequency: 'DAILY' }, { allowMobileData: true },
    ]);
  });

  it('picks and clears a country', async () => {
    const { updates } = renderWithServices(<SettingsScreen />, { settings: { country: 'NZ' } });
    expect(screen.getByTestId('settings-country-button').props.accessibilityLabel).toContain('New Zealand');
    fireEvent.press(screen.getByTestId('settings-country-button'));
    fireEvent.changeText(await screen.findByTestId('settings-country-search'), 'norw');
    fireEvent.press(await screen.findByTestId('settings-country-NO'));
    await act(async () => {});
    expect(updates).toEqual([{ country: 'NO' }]);

    fireEvent.press(screen.getByTestId('settings-country-button'));
    fireEvent.press(await screen.findByTestId('settings-country-none'));
    await act(async () => {});
    expect(updates).toEqual([{ country: 'NO' }, { country: null }]);
  });

  it('shows the last sync time or Never', () => {
    renderWithServices(<SettingsScreen />);
    expect(screen.getByText(en('settings_last_sync_label', { p1: en('settings_last_sync_never') }))).toBeTruthy();
  });

  it('syncs now and shows a message that can be dismissed', async () => {
    const syncNow = jest.fn().mockResolvedValue({ kind: 'updated', bundleVersion: 'v9', documentsDownloaded: 2 });
    renderWithServices(<SettingsScreen />, { syncNow });
    fireEvent.press(screen.getByTestId('settings-sync-now-button'));
    expect(await screen.findByText(en('settings_sync_updated_message', { p1: 'v9', p2: 2 }))).toBeTruthy();
    expect(syncNow).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getByTestId('settings-sync-message-dismiss'));
    expect(screen.queryByTestId('settings-sync-message')).toBeNull();
  });

  it('says so when sync is not available in this build', async () => {
    renderWithServices(<SettingsScreen />, { syncAvailable: false, syncNow: async () => ({ kind: 'unavailable' }) });
    expect(screen.getAllByText(en('settings_sync_unavailable_message'))).toHaveLength(1);
    fireEvent.press(screen.getByTestId('settings-sync-now-button'));
    await waitFor(() => expect(screen.queryByTestId('settings-sync-message')).toBeTruthy());
    expect(screen.getAllByText(en('settings_sync_unavailable_message'))).toHaveLength(2);
  });

  it('opens the About screen', () => {
    renderWithServices(<SettingsScreen />);
    fireEvent.press(screen.getByTestId('settings-about-button'));
    expect(mockPush).toHaveBeenCalledWith('/about');
  });

  it('words every sync outcome', () => {
    const t = (k: Parameters<typeof translate>[0], v?: Record<string, string | number>) => translate(k, 'en', v);
    expect(syncMessageText({ kind: 'upToDate', bundleVersion: 'v1' }, t)).toBe(en('settings_sync_up_to_date_message', { p1: 'v1' }));
    expect(syncMessageText({ kind: 'rejectedSignature' }, t)).toBe(en('settings_sync_rejected_signature_message'));
    expect(syncMessageText({ kind: 'failed', reason: 'offline' }, t)).toBe(en('settings_sync_failed_message', { p1: 'offline' }));
  });
});

describe('About', () => {
  it('shows the disclaimer and the privacy statement', async () => {
    renderWithServices(<AboutScreen />);
    expect(screen.getByText(en('about_disclaimer'))).toBeTruthy();
    expect(screen.getByText(en('about_privacy'))).toBeTruthy();
    expect(await screen.findByTestId('about-source-doc-1')).toBeTruthy();
  });

  it('lists the title, agency, licence and original link of every installed document', async () => {
    renderWithServices(<AboutScreen />);
    expect(await screen.findByText('Prepare')).toBeTruthy();
    expect(screen.getByText(en('about_source_details', { p1: 'UK Government', p2: '2024-02-02', p3: 'GB' }))).toBeTruthy();
    expect(screen.getByText(en('about_source_licence', { p1: DOCUMENT.licence }))).toBeTruthy();
    expect(screen.getByText(en('about_source_link', { p1: DOCUMENT.sourceUri }))).toBeTruthy();
  });

  it('says so when no content is installed', async () => {
    renderWithServices(<AboutScreen />, { store: new FakeStore([], []) });
    expect(await screen.findByText(en('about_sources_empty'))).toBeTruthy();
  });
});
