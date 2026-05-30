import {NotificationItem, NotificationPrefRow, PagedResponse, req} from './core';

export const getNotifications = (page = 0, size = 20) =>
  req<PagedResponse<NotificationItem>>(`/notifications?page=${page}&size=${size}`);

export const getUnreadCount = () => req<{count: number}>('/notifications/unread-count');

export const markNotificationRead = (id: string) =>
  req<void>(`/notifications/${id}/read`, {method: 'POST'});

export const markAllNotificationsRead = () =>
  req<void>('/notifications/read-all', {method: 'POST'});

export const getNotificationPreferences = () =>
  req<NotificationPrefRow[]>('/notifications/preferences');

export const updateNotificationPreference = (
  eventType: string,
  channel: 'IN_APP' | 'EMAIL',
  enabled: boolean,
) =>
  req<NotificationPrefRow>('/notifications/preferences', {
    method: 'PUT',
    body: JSON.stringify({eventType, channel, enabled}),
  });
