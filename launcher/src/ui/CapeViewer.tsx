import { useEffect, useRef } from "react";
import { FlyingAnimation, IdleAnimation, SkinViewer, WalkingAnimation } from "skinview3d";
import { PET_OFFSET, PET_SCALE, animateChroma, buildExtra, disposeExtra, type ExtraModel } from "../lib/extras";

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
export function CapeViewer({ frames, frameTimeMs, elytra, skinUrl, slim, pose, hat, pet, width = 260, height = 340 }: { frames: HTMLCanvasElement[]; frameTimeMs: number; elytra: boolean; skinUrl?: string | null; slim?: boolean; pose: Pose; hat?: ExtraModel | null; pet?: ExtraModel | null; width?: number; height?: number }) {
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
    // Without an explicit model skinview3d guesses it from the arms.
    viewer.current.loadSkin(skinUrl, slim === undefined ? undefined : { model: slim ? "slim" : "default" }).catch(() => {
      // Skin servers can be unreachable offline; the stand-in stays.
    });
  }, [skinUrl, slim]);

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

  // Hat on the head, pet floating beside the shoulder (bobbing, the cube spinning).
  useEffect(() => {
    const v = viewer.current;
    if (!v || (!hat && !pet)) return;
    const hatGroup = hat ? buildExtra(hat) : null;
    const petGroup = pet ? buildExtra(pet) : null;
    if (hatGroup) v.playerObject.skin.head.add(hatGroup);
    if (petGroup) {
      petGroup.scale.setScalar(PET_SCALE);
      v.playerObject.skin.add(petGroup);
    }
    let frame = 0;
    const tick = (now: number) => {
      const t = now / 50; // game ticks, like the mod
      if (petGroup) {
        petGroup.position.copy(PET_OFFSET);
        petGroup.position.y -= Math.sin(t * 0.08) * 0.07 * 16;
        if (pet?.spin) petGroup.rotation.set((-t * 1.3 * Math.PI) / 180, (-t * 2.4 * Math.PI) / 180, 0);
        else petGroup.rotation.set(0, (-Math.sin(t * 0.035) * 12 * Math.PI) / 180, (-Math.sin(t * 0.05) * 4 * Math.PI) / 180);
        animateChroma(petGroup, now);
      }
      if (hatGroup) animateChroma(hatGroup, now);
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(frame);
      if (hatGroup) {
        hatGroup.removeFromParent();
        disposeExtra(hatGroup);
      }
      if (petGroup) {
        petGroup.removeFromParent();
        disposeExtra(petGroup);
      }
    };
  }, [hat, pet, width, height]);

  return <canvas ref={canvas} className="cursor-grab active:cursor-grabbing" aria-label="3D-Vorschau" />;
}
