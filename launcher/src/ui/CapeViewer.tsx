import { useEffect, useRef } from "react";
import { FlyingAnimation, IdleAnimation, SkinViewer, WalkingAnimation } from "skinview3d";

/** Plain stand-in skin when no account skin is available (dark outfit, light face). */
function fallbackSkin(): HTMLCanvasElement {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#2a2340";
  ctx.fillRect(0, 0, 64, 64);
  ctx.fillStyle = "#3b3158";
  ctx.fillRect(16, 16, 40, 16);
  ctx.fillStyle = "#c9a68a";
  ctx.fillRect(0, 0, 32, 16);
  ctx.fillStyle = "#3a2a20";
  ctx.fillRect(8, 0, 16, 8);
  ctx.fillRect(0, 8, 32, 2);
  ctx.fillStyle = "#1d1729";
  ctx.fillRect(0, 16, 16, 16);
  ctx.fillRect(16, 48, 32, 16);
  return c;
}

export type Pose = "walk" | "fly" | "idle";

/**
 * 3D player with the cape (skinview3d, MIT). `frames` are rendered cape textures;
 * they cycle every `frameTimeMs` by redrawing the viewer's cape canvas in place.
 */
export function CapeViewer({ frames, frameTimeMs, elytra, skinUrl, pose, width = 260, height = 340 }: { frames: HTMLCanvasElement[]; frameTimeMs: number; elytra: boolean; skinUrl?: string | null; pose: Pose; width?: number; height?: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const viewer = useRef<SkinViewer | null>(null);

  useEffect(() => {
    const v = new SkinViewer({ canvas: canvas.current!, width, height, skin: fallbackSkin() });
    v.fov = 40;
    v.zoom = 0.82;
    v.autoRotate = true;
    v.autoRotateSpeed = 0.6;
    // Start looking at the back, where the cape is.
    v.playerObject.rotation.y = Math.PI;
    v.controls.enableZoom = false;
    viewer.current = v;
    return () => {
      v.dispose();
      viewer.current = null;
    };
  }, [width, height]);

  useEffect(() => {
    if (!skinUrl || !viewer.current) return;
    viewer.current.loadSkin(skinUrl).catch(() => {
      // Skin servers can be unreachable offline; the stand-in stays.
    });
  }, [skinUrl]);

  useEffect(() => {
    const v = viewer.current;
    if (!v) return;
    v.animation = pose === "fly" ? new FlyingAnimation() : pose === "idle" ? new IdleAnimation() : new WalkingAnimation();
    if (pose === "walk" && v.animation) v.animation.speed = 0.6;
  }, [pose]);

  useEffect(() => {
    const v = viewer.current;
    if (!v || frames.length === 0) return;
    v.loadCape(frames[0], { backEquipment: elytra ? "elytra" : "cape" });
    if (frames.length < 2) return;
    let i = 0;
    const timer = setInterval(() => {
      i = (i + 1) % frames.length;
      const ctx = v.capeCanvas.getContext("2d")!;
      ctx.clearRect(0, 0, v.capeCanvas.width, v.capeCanvas.height);
      ctx.drawImage(frames[i], 0, 0, v.capeCanvas.width, v.capeCanvas.height);
      // capeTexture is internal to skinview3d; flag it so three.js re-uploads the canvas.
      const tex = (v as unknown as { capeTexture: { needsUpdate: boolean } | null }).capeTexture;
      if (tex) tex.needsUpdate = true;
    }, Math.max(40, frameTimeMs));
    return () => clearInterval(timer);
  }, [frames, frameTimeMs, elytra]);

  return <canvas ref={canvas} className="cursor-grab active:cursor-grabbing" aria-label="3D-Vorschau der Cape" />;
}
