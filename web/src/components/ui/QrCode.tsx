import { useEffect, useState } from 'react';
import { Spinner } from './primitives';

/** QR code rendered as an SVG data URL; always dark-on-white so phone cameras read it in any theme. */
export function QrCode({ value, label, size = 224 }: { value: string; label: string; size?: number }) {
  const [src, setSrc] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setSrc(null);
    setError(null);
    // The encoder is loaded on first use, so screens without a QR code do not carry it.
    import('qrcode')
      .then(({ default: QRCode }) => QRCode.toString(value, { type: 'svg', errorCorrectionLevel: 'M', margin: 2, color: { dark: '#000000', light: '#ffffff' } }))
      .then((svg) => {
        if (!cancelled) setSrc(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not draw the QR code.');
      });
    return () => {
      cancelled = true;
    };
  }, [value]);

  if (error) return <p className="text-sm text-danger">{error}</p>;
  if (!src) return <Spinner label="Drawing QR code" />;
  return <img src={src} alt={label} width={size} height={size} className="rounded-xl bg-white p-1" style={{ maxWidth: '100%', height: 'auto' }} />;
}
