import '@testing-library/jest-dom';
import { render, screen, fireEvent, waitFor } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import ExclusionsWorkspaceView from '@/views/ExclusionsWorkspaceView.vue';
import { messages } from '@/locales/messages';

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages
});

function setup() {
  return render(ExclusionsWorkspaceView, {
    global: {
      plugins: [i18n]
    }
  });
}

describe('ExclusionsWorkspaceView', () => {
  it('filters results by search query', async () => {
    setup();

    const entity = await screen.findByText('Al-Qalam Foundation', {
      selector: '.entity-label'
    });
    expect(entity).toBeInTheDocument();

    const search = screen.getByRole('searchbox', { name: /search exclusions/i });
    await fireEvent.update(search, 'Halaqa');

    await waitFor(() => {
      expect(
        screen.queryByText('Al-Qalam Foundation', {
          selector: '.entity-label'
        })
      ).not.toBeInTheDocument();
      expect(screen.getByText('Daily Halaqa 231: Mercy and Neighbours')).toBeInTheDocument();
    });
  });

  it('shows bulk actions when rows are selected and can clear them', async () => {
    setup();

    const rowCheckbox = await screen.findByRole('checkbox', {
      name: /select al-qalam foundation for bulk action/i
    });
    await fireEvent.click(rowCheckbox);

    const summary = await screen.findByText('1 selected');
    expect(summary).toBeInTheDocument();

    const bulkInclude = screen.getByRole('button', { name: /move selected to include/i });
    await fireEvent.click(bulkInclude);

    await waitFor(() => {
      expect(
        screen.queryByText('Al-Qalam Foundation', {
          selector: '.entity-label'
        })
      ).not.toBeInTheDocument();
    });
    expect(screen.getByRole('status')).toHaveTextContent(/exclusions moved to include/i);
  });

  it('adds a new exclusion via the dialog and restores focus to the trigger', async () => {
    setup();

    const trigger = screen.getByRole('button', { name: /add exclusion/i });
    await fireEvent.click(trigger);

    const targetField = await screen.findByLabelText(/resource identifier/i);
    await waitFor(() => expect(targetField).toHaveFocus());

    await fireEvent.update(targetField, 'Friday Circle');
    const typeSelect = screen.getByLabelText(/resource type/i);
    await fireEvent.update(typeSelect, 'playlist');
    const reasonField = screen.getByLabelText(/reason/i);
    await fireEvent.update(reasonField, 'Manual QA hold');

    const createButton = screen.getByRole('button', { name: /save exclusion/i });
    await fireEvent.click(createButton);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Friday Circle')).toBeInTheDocument());
  });
});
