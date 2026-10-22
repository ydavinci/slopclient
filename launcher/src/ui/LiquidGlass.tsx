import { forwardRef, useCallback, useId, useLayoutEffect, useRef, useState, type CSSProperties, type HTMLAttributes, type ReactNode } from "react";

/**
 * Liquid Glass in the manner of iOS 26: the backdrop is bent at the rim like by a
 * thick convex lens (an SVG displacement filter with a map made for the element's
 * size and corner radius), slightly split into colours, blurred a little and
 * brightened; a specular rim and a highlight that follows the pointer sit on top.
 *
 * WebView2 is Chromium, which supports SVG filters in backdrop-filter. Elsewhere the
 * url() part is ignored and the glass falls back to blur and saturation.
 */

/** Displacement map: red and green carry the x/y offset (0.5 = none), strongest along the rim. */
function lensMap(w: number, h: number, radius: number, bezel: number): string {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const ctx = c.getContext("2d")!;
  const img = ctx.createImageData(w, h);
  // Signed distance to the rounded rectangle's edge, positive inside.
  const sdf = (x: number, y: number) => {
    const qx = Math.abs(x - w / 2) - (w / 2 - radius);
    const qy = Math.abs(y - h / 2) - (h / 2 - radius);
    const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
    const inside = Math.min(Math.max(qx, qy), 0);
    return radius - (outside + inside);
  };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const d = sdf(x + 0.5, y + 0.5);
      const i = (y * w + x) * 4;
      let dx = 0,
        dy = 0;
      if (d > 0 && d < bezel) {
        // Outward normal from the distance gradient.
        const nx = sdf(x - 0.5, y + 0.5) - sdf(x + 1.5, y + 0.5);
        const ny = sdf(x + 0.5, y - 0.5) - sdf(x + 0.5, y + 1.5);
        const len = Math.hypot(nx, ny) || 1;
        // Convex bezel: the closer to the edge, the further inward it samples (magnified rim).
        const t = 1 - d / bezel;
        const k = t * t * (3 - 2 * t);
        dx = (-nx / len) * k;
        dy = (-ny / len) * k;
      }
      img.data[i] = Math.round(128 + dx * 127);
      img.data[i + 1] = Math.round(128 + dy * 127);
      img.data[i + 2] = 128;
      img.data[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}

export interface LiquidGlassProps extends HTMLAttributes<HTMLDivElement> {
  radius: number;
  /** Width of the refracting rim in px. */
  bezel?: number;
  /** Strongest displacement in px. */
  strength?: number;
  /** Light that follows the pointer. */
  pointerLight?: boolean;
  children?: ReactNode;
}

export const LiquidGlass = forwardRef<HTMLDivElement, LiquidGlassProps>(function LiquidGlass(
  { radius, bezel = 16, strength = 34, pointerLight = true, className = "", style, children, onPointerMove, onPointerLeave, ...rest },
  forwarded,
) {
  const id = useId().replace(/:/g, "");
  const local = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const [map, setMap] = useState<string | null>(null);

  const setRef = useCallback(
    (el: HTMLDivElement | null) => {
      local.current = el;
      if (typeof forwarded === "function") forwarded(el);
      else if (forwarded) forwarded.current = el;
    },
    [forwarded],
  );

  useLayoutEffect(() => {
    const el = local.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      const w = Math.round(entry.contentRect.width + 0), h = Math.round(entry.contentRect.height + 0);
      const box = el.getBoundingClientRect();
      setSize((s) => (s && s.w === Math.round(box.width) && s.h === Math.round(box.height) ? s : { w: Math.round(box.width) || w, h: Math.round(box.height) || h }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useLayoutEffect(() => {
    if (!size || size.w < 4 || size.h < 4) return;
    setMap(lensMap(size.w, size.h, Math.min(radius, size.w / 2, size.h / 2), bezel));
  }, [size, radius, bezel]);

  const filter = map ? `url(#${id}) blur(2.5px) saturate(185%) brightness(1.08)` : "blur(14px) saturate(170%)";
  const glassStyle: CSSProperties = {
    ...style,
    borderRadius: radius,
    backdropFilter: filter,
    WebkitBackdropFilter: filter,
  };

  return (
    <div
      ref={setRef}
      {...rest}
      className={`liquid-glass ${className}`}
      style={glassStyle}
      onPointerMove={(e) => {
        if (pointerLight && local.current) {
          const r = local.current.getBoundingClientRect();
          local.current.style.setProperty("--lx", `${e.clientX - r.left}px`);
          local.current.style.setProperty("--ly", `${e.clientY - r.top}px`);
          local.current.style.setProperty("--lo", "1");
        }
        onPointerMove?.(e);
      }}
      onPointerLeave={(e) => {
        local.current?.style.setProperty("--lo", "0");
        onPointerLeave?.(e);
      }}
    >
      {map && size && (
        <svg aria-hidden width="0" height="0" className="pointer-events-none absolute">
          <filter id={id} x="0" y="0" width={size.w} height={size.h} filterUnits="userSpaceOnUse" colorInterpolationFilters="sRGB">
            <feImage href={map} x="0" y="0" width={size.w} height={size.h} preserveAspectRatio="none" result="map" />
            {/* Each colour bends a little differently: a faint prism fringe at the rim. */}
            <feDisplacementMap in="SourceGraphic" in2="map" scale={strength} xChannelSelector="R" yChannelSelector="G" result="r" />
            <feDisplacementMap in="SourceGraphic" in2="map" scale={strength * 0.965} xChannelSelector="R" yChannelSelector="G" result="g" />
            <feDisplacementMap in="SourceGraphic" in2="map" scale={strength * 0.93} xChannelSelector="R" yChannelSelector="G" result="b" />
            <feColorMatrix in="r" type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="rr" />
            <feColorMatrix in="g" type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="gg" />
            <feColorMatrix in="b" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="bb" />
            <feBlend in="rr" in2="gg" mode="screen" result="rg" />
            <feBlend in="rg" in2="bb" mode="screen" />
          </filter>
        </svg>
      )}
      {children}
    </div>
  );
});
