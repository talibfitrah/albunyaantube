# Frontend Test Utilities

Helpers for writing unit and integration tests with Vitest and Vue Test Utils.

## Test Utilities

### `mockApiClient.ts`
Mock Axios client for testing API calls.

**Usage**:
```ts
import { createMockApiClient, mockSuccessResponse, mockErrorResponse } from './utils/mockApiClient';

describe('MyService', () => {
  it('fetches data successfully', async () => {
    const mockClient = createMockApiClient();
    mockClient.get.mockResolvedValue(mockSuccessResponse({ id: '1', name: 'Test' }));

    const service = new MyService(mockClient);
    const data = await service.getData();

    expect(data).toEqual({ id: '1', name: 'Test' });
    expect(mockClient.get).toHaveBeenCalledWith('/api/data');
  });

  it('handles errors', async () => {
    const mockClient = createMockApiClient();
    mockClient.get.mockRejectedValue(mockErrorResponse(500, 'Server Error'));

    const service = new MyService(mockClient);
    await expect(service.getData()).rejects.toThrow();
  });
});
```

### `mockFirebaseAuth.ts`
Mock Firebase Authentication for testing.

**Usage**:
```ts
import { mockFirebaseAuth, mockAuthUser } from './utils/mockFirebaseAuth';

describe('AuthService', () => {
  it('signs in user', async () => {
    const auth = mockFirebaseAuth();
    const user = mockAuthUser({ email: 'admin@example.com' });
    auth.signInWithEmailAndPassword.mockResolvedValue({ user });

    const result = await signIn(auth, 'admin@example.com', 'password');

    expect(result.user.email).toBe('admin@example.com');
  });
});
```

### `testData.ts`
Test data builders for creating mock domain objects.

**Usage**:
```ts
import {
  createMockVideo,
  createMockChannel,
  createMockVideoList
} from './utils/testData';

describe('VideoComponent', () => {
  it('displays video title', () => {
    const video = createMockVideo({ title: 'My Test Video' });
    const wrapper = mount(VideoComponent, { props: { video } });

    expect(wrapper.text()).toContain('My Test Video');
  });

  it('displays list of videos', () => {
    const videos = createMockVideoList(5);
    const wrapper = mount(VideoList, { props: { videos } });

    expect(wrapper.findAll('.video-item')).toHaveLength(5);
  });
});
```

## Running Tests

### All Tests
```bash
cd frontend
npm test
```

### Watch Mode
```bash
npm run test:watch
```

### Coverage
```bash
npm run test:coverage
```

### Specific File
```bash
npm test -- categoryService.spec.ts
```

## Writing Tests

### Component Test Example

```ts
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import MyComponent from '@/components/MyComponent.vue';
import { createMockVideo } from './utils/testData';

describe('MyComponent', () => {
  it('renders correctly', () => {
    const video = createMockVideo();
    const wrapper = mount(MyComponent, {
      props: { video }
    });

    expect(wrapper.find('.title').text()).toBe(video.title);
  });

  it('emits event on click', async () => {
    const wrapper = mount(MyComponent);
    await wrapper.find('button').trigger('click');

    expect(wrapper.emitted('click')).toBeTruthy();
  });
});
```

### Service Test Example

```ts
import { describe, it, expect } from 'vitest';
import { createMockApiClient, mockSuccessResponse } from './utils/mockApiClient';
import { createMockVideoList } from './utils/testData';
import VideoService from '@/services/videoService';

describe('VideoService', () => {
  it('fetches videos', async () => {
    const mockClient = createMockApiClient();
    const videos = createMockVideoList(3);
    mockClient.get.mockResolvedValue(mockSuccessResponse(videos));

    const service = new VideoService(mockClient);
    const result = await service.getVideos();

    expect(result).toHaveLength(3);
    expect(mockClient.get).toHaveBeenCalledWith('/api/videos');
  });
});
```

### Store Test Example

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useVideoStore } from '@/stores/video';
import { createMockVideoList } from './utils/testData';

describe('Video Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('loads videos', async () => {
    const store = useVideoStore();
    const videos = createMockVideoList(5);

    // Mock API call
    store.videos = videos;

    expect(store.videos).toHaveLength(5);
  });
});
```

## Best Practices

1. **Use Test Utilities**: Don't create mock data manually, use the builders
2. **Test User Behavior**: Test what users see and do, not implementation details
3. **Keep Tests Fast**: Mock external dependencies (API calls, Firebase)
4. **One Assertion Per Test**: Each test should verify one thing
5. **Descriptive Names**: Use `describe('Component', () => it('does something', ...))`
6. **Clean Up**: Use `beforeEach` and `afterEach` for setup/teardown

## Troubleshooting

### "Cannot find module" errors
- Ensure `vite.config.ts` has the correct `alias` configuration
- Check that imports use `@/` prefix for src files

### "ReferenceError: window is not defined"
- Ensure `environment: 'jsdom'` is set in `vite.config.ts`

### Tests timing out
- Increase timeout: `it('test', async () => { ... }, { timeout: 10000 })`
- Check for unresolved promises

### Mock not working
- Ensure mocks are set up before the tested code runs
- Use `vi.fn()` for function mocks
- Use `vi.mock()` for module mocks

## Resources

- [Vitest Documentation](https://vitest.dev/)
- [Vue Test Utils](https://test-utils.vuejs.org/)
- [Testing Library](https://testing-library.com/docs/vue-testing-library/intro/)
