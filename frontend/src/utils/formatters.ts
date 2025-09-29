export function formatNumber(value: number, locale: string): string {
  return new Intl.NumberFormat(locale).format(value);
}

export function formatDate(value: string, locale: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}

export function formatDuration(seconds: number, locale: string): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '–';
  }

  const rounded = Math.floor(seconds);
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const secs = rounded % 60;

  const hourFormatter = new Intl.NumberFormat(locale, { useGrouping: false, minimumIntegerDigits: 1 });
  const minuteFormatter = new Intl.NumberFormat(locale, {
    useGrouping: false,
    minimumIntegerDigits: hours > 0 ? 2 : 1
  });
  const secondFormatter = new Intl.NumberFormat(locale, { useGrouping: false, minimumIntegerDigits: 2 });

  if (hours > 0) {
    return `${hourFormatter.format(hours)}:${minuteFormatter.format(minutes)}:${secondFormatter.format(secs)}`;
  }

  return `${minuteFormatter.format(minutes)}:${secondFormatter.format(secs)}`;
}
