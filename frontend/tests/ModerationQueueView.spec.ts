import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockedFunction } from 'vitest';
import ModerationQueueView from '@/views/ModerationQueueView.vue';
import { messages } from '@/locales/messages';
import type { ModerationProposal } from '@/types/moderation';
import {
  fetchProposalsPage,
  approveProposal,
  rejectProposal
} from '@/services/moderation';
import { setAuditEventSink, type AuditEventDetail } from '@/services/audit';

vi.mock('@/services/moderation', () => ({
  fetchProposalsPage: vi.fn(),
  approveProposal: vi.fn(),
  rejectProposal: vi.fn()
}));

const baseProposal: ModerationProposal = {
  id: 'proposal-1',
  kind: 'VIDEO',
  ytId: 'abc123',
  status: 'PENDING',
  suggestedCategories: [],
  proposer: {
    id: 'user-1',
    email: 'moderator@example.com',
    displayName: 'Moderator Example',
    roles: ['MODERATOR'],
    status: 'ACTIVE',
    lastLoginAt: null,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z'
  },
  notes: null,
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z'
};

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function createPage(proposals: ModerationProposal[]) {
  return {
    data: proposals,
    pageInfo: {
      cursor: null,
      nextCursor: null,
      hasNext: false,
      limit: proposals.length
    }
  };
}

function setupRender() {
  return render(ModerationQueueView, {
    global: {
      plugins: [i18n]
    }
  });
}

describe('ModerationQueueView', () => {
  let fetchMock: MockedFunction<typeof fetchProposalsPage>;
  let approveMock: MockedFunction<typeof approveProposal>;
  let rejectMock: MockedFunction<typeof rejectProposal>;

  beforeEach(() => {
    fetchMock = fetchProposalsPage as unknown as MockedFunction<typeof fetchProposalsPage>;
    approveMock = approveProposal as unknown as MockedFunction<typeof approveProposal>;
    rejectMock = rejectProposal as unknown as MockedFunction<typeof rejectProposal>;

    fetchMock.mockResolvedValue(createPage([baseProposal]));
    approveMock.mockResolvedValue({ ...baseProposal, status: 'APPROVED' });
    rejectMock.mockResolvedValue({ ...baseProposal, status: 'REJECTED', decisionReason: 'Duplicate' });
    setAuditEventSink(null);
  });

  afterEach(() => {
    vi.clearAllMocks();
    setAuditEventSink(null);
  });

  it('emits audit event when approving a proposal', async () => {
    const events: AuditEventDetail[] = [];
    setAuditEventSink(detail => {
      events.push(detail);
    });

    setupRender();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const approveButton = await screen.findByRole('button', { name: /approve/i });
    await fireEvent.click(approveButton);

    await waitFor(() => expect(approveMock).toHaveBeenCalledWith('proposal-1'));

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      name: 'moderation:approve',
      proposalId: 'proposal-1'
    });
  });

  it('manages reject dialog focus and emits audit event with trimmed reason', async () => {
    const events: AuditEventDetail[] = [];
    setAuditEventSink(detail => {
      events.push(detail);
    });

    setupRender();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const rejectButton = await screen.findByRole('button', { name: /reject/i });

    await fireEvent.click(rejectButton);

    const reasonField = await screen.findByLabelText(/reason/i);
    await waitFor(() => expect(reasonField).toHaveFocus());

    const cancelButton = screen.getByRole('button', { name: /cancel/i });
    await fireEvent.click(cancelButton);
    await waitFor(() => expect(rejectButton).toHaveFocus());

    await fireEvent.click(rejectButton);
    const textarea = await screen.findByLabelText(/reason/i);
    await fireEvent.update(textarea, '  Duplicate submission  ');

    const submitButton = screen.getByRole('button', { name: /submit decision/i });
    await fireEvent.click(submitButton);

    await waitFor(() => expect(rejectMock).toHaveBeenCalledWith('proposal-1', 'Duplicate submission'));

    const rejectEvent = events.find(event => event.name === 'moderation:reject');
    expect(rejectEvent).toBeDefined();
    expect(rejectEvent).toMatchObject({
      proposalId: 'proposal-1',
      metadata: { reason: 'Duplicate submission' }
    });

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const refreshedRejectButton = await screen.findByRole('button', { name: /reject/i });
    await waitFor(() => expect(refreshedRejectButton).toHaveFocus());
  });

  it('traps focus within the reject dialog when tabbing', async () => {
    setupRender();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const rejectButton = await screen.findByRole('button', { name: /reject/i });
    await fireEvent.click(rejectButton);

    const reasonField = await screen.findByLabelText(/reason/i);

    await waitFor(() => expect(reasonField).toHaveFocus());

    const submitButton = await screen.findByRole('button', { name: /submit/i });
    const cancelButton = screen.getByRole('button', { name: /cancel/i });

    await fireEvent.keyDown(reasonField, { key: 'Tab', shiftKey: true });
    await waitFor(() => expect(submitButton).toHaveFocus());

    await fireEvent.keyDown(submitButton, { key: 'Tab', shiftKey: true });
    await waitFor(() => expect(cancelButton).toHaveFocus());

    await fireEvent.keyDown(cancelButton, { key: 'Tab' });
    await waitFor(() => expect(submitButton).toHaveFocus());

    await fireEvent.keyDown(submitButton, { key: 'Tab' });
    await waitFor(() => expect(reasonField).toHaveFocus());
  });
});
