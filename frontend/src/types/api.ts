/**
 * Re-exports of generated OpenAPI types for easier imports.
 *
 * This file provides convenience exports from the generated schema.
 * DO NOT edit the generated schema directly - it's regenerated from api-specification.yaml
 *
 * To regenerate: npm run generate:api
 */

import type { components, operations, paths } from '../generated/api/schema'

// ============================================================================
// Component Schemas (DTOs)
// ============================================================================

export type Category = components['schemas']['Category']
export type CategoryDto = components['schemas']['CategoryDto']
export type Channel = components['schemas']['Channel']
export type Playlist = components['schemas']['Playlist']
export type Video = components['schemas']['Video']

// Content Item Types
export type ContentItemDto = components['schemas']['ContentItemDto']
export type ContentItem = components['schemas']['ContentItem']
export type ContentLibraryResponse = components['schemas']['ContentLibraryResponse']

// Pagination
export type PageInfo = components['schemas']['PageInfo']
export type CursorPageDto = components['schemas']['CursorPageDto']

// YouTube Search
export type EnrichedSearchResult = components['schemas']['EnrichedSearchResult']
export type SearchPageResponse = components['schemas']['SearchPageResponse']
export type ChannelDetailsDto = components['schemas']['ChannelDetailsDto']
export type PlaylistDetailsDto = components['schemas']['PlaylistDetailsDto']
export type StreamDetailsDto = components['schemas']['StreamDetailsDto']
export type StreamItemDto = components['schemas']['StreamItemDto']
export type PlaylistItemDto = components['schemas']['PlaylistItemDto']
export type PaginatedStreamItems = components['schemas']['PaginatedStreamItems']
export type PaginatedPlaylistItems = components['schemas']['PaginatedPlaylistItems']

// Approval Workflow
export type ApprovalMetadata = components['schemas']['ApprovalMetadata']
export type PendingApprovalDto = components['schemas']['PendingApprovalDto']
export type ApprovalRequestDto = components['schemas']['ApprovalRequestDto']
export type ApprovalResponseDto = components['schemas']['ApprovalResponseDto']
export type RejectionRequestDto = components['schemas']['RejectionRequestDto']

// Downloads
export type DownloadTokenRequest = components['schemas']['DownloadTokenRequest']
export type DownloadTokenDto = components['schemas']['DownloadTokenDto']
export type DownloadManifestDto = components['schemas']['DownloadManifestDto']
export type DownloadPolicyDto = components['schemas']['DownloadPolicyDto']
export type StreamOption = components['schemas']['StreamOption']

// User Management
export type User = components['schemas']['User']

// Audit
export type AuditLog = components['schemas']['AuditLog']

// Errors
export type ErrorResponse = components['schemas']['ErrorResponse']

// Exclusions
export type ExcludedItems = components['schemas']['ExcludedItems']

// Bulk Operations
export type BulkActionRequest = components['schemas']['BulkActionRequest']
export type BulkActionItem = components['schemas']['BulkActionItem']
export type BulkActionResponse = components['schemas']['BulkActionResponse']
export type BulkCategoryAssignmentRequest = components['schemas']['BulkCategoryAssignmentRequest']

// ============================================================================
// Operation Types (Request/Response Types)
// ============================================================================

// Public Content API
export type GetPublicContentParams = operations['getPublicContent']['parameters']['query']
export type GetPublicContentResponse = operations['getPublicContent']['responses']['200']['content']['application/json']

export type ListPublicCategoriesResponse = operations['listPublicCategories']['responses']['200']['content']['application/json']

// Admin Categories
export type ListCategoriesResponse = operations['listCategories']['responses']['200']['content']['application/json']
export type CreateCategoryRequest = operations['createCategory']['requestBody']['content']['application/json']

// Admin Channels
export type ListChannelsParams = operations['listChannels']['parameters']['query']
export type ListChannelsResponse = operations['listChannels']['responses']['200']['content']['application/json']

// Admin YouTube Search
export type YoutubeSearchUnifiedParams = operations['youtubeSearchUnified']['parameters']['query']
export type YoutubeSearchChannelsParams = operations['youtubeSearchChannels']['parameters']['query']
export type YoutubeSearchPlaylistsParams = operations['youtubeSearchPlaylists']['parameters']['query']
export type YoutubeSearchVideosParams = operations['youtubeSearchVideos']['parameters']['query']

// Admin Approvals
export type ListPendingApprovalsParams = operations['listPendingApprovals']['parameters']['query']

// Import/Export
export type ExportAllParams = operations['exportAll']['parameters']['query']

// ============================================================================
// Path Types (for strongly-typed API client)
// ============================================================================

export type Paths = paths

// ============================================================================
// Type Guards
// ============================================================================

export function isChannel(item: ContentItem): item is Channel {
  return 'youtubeId' in item && 'subscribers' in item
}

export function isPlaylist(item: ContentItem): item is Playlist {
  return 'youtubeId' in item && 'itemCount' in item && !('subscribers' in item)
}

export function isVideo(item: ContentItem): item is Video {
  return 'youtubeId' in item && 'durationSeconds' in item
}

export function isContentItemDto(item: ContentItemDto): item is ContentItemDto {
  return item.type === 'CHANNEL' || item.type === 'PLAYLIST' || item.type === 'VIDEO'
}
