import { useEffect, useRef } from "react";
import { FlyingAnimation, IdleAnimation, SkinViewer, WalkingAnimation } from "skinview3d";
import { fallbackSkin } from "../lib/skin";
import { PET_PLACE, animateChroma, buildHat, buildPet, disposeExtra, type BuiltPet, type HatModel, type PetModel } from "../lib/extras";
import { buildAccessory, type AccessoryModel } from "../lib/accessory";
import { asWorn } from "../lib/cape";

export { fallbackSkin };

export type Pose = "walk" | "fly" | "idle";

/**
 * 3D player with the cape (skinview3d, MIT). `frames` are rendered cape textures;
 * they cycle every `frameTimeMs` by redrawing the viewer's cape canvas in place.
 */
export function CapeViewer({ frames, frameTimeMs, elytra, skinUrl, slim, pose, hat, pet, accessory, width = 260, height = 340 }: { frames: HTMLCanvasElement[]; frameTimeMs: number; elytra: boolean; skinUrl?: string | null; slim?: boolean; pose: Pose; hat?: HatModel | null; pet?: PetModel | null; accessory?: AccessoryModel | null; width?: number; height?: number }) {
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
    const worn = frames.map((f) => asWorn(f, elytra));
    v.loadCape(worn[0], { backEquipment: elytra ? "elytra" : "cape" });
    if (worn.length < 2) return;
    let i = 0;
    const timer = setInterval(() => {
      i = (i + 1) % worn.length;
      const ctx = v.capeCanvas.getContext("2d")!;
      ctx.clearRect(0, 0, v.capeCanvas.width, v.capeCanvas.height);
      ctx.drawImage(worn[i], 0, 0, v.capeCanvas.width, v.capeCanvas.height);
      // capeTexture is internal to skinview3d; flag it so three.js re-uploads the canvas.
      const tex = (v as unknown as { capeTexture: { needsUpdate: boolean } | null }).capeTexture;
      if (tex) tex.needsUpdate = true;
    }, Math.max(40, frameTimeMs));
    return () => clearInterval(timer);
  }, [frames, frameTimeMs, elytra]);

  // The accessory on the body or head, where its catalogue entry places it.
  useEffect(() => {
    const v = viewer.current;
    if (!v || !accessory) return;
    let group: Awaited<ReturnType<typeof buildAccessory>> | null = null;
    let cancelled = false;
    buildAccessory(accessory)
      .then((g) => {
        if (cancelled || !viewer.current) return disposeExtra(g);
        group = g;
        (accessory.attach === "head" ? v.playerObject.skin.head : v.playerObject.skin).add(g);
      })
      .catch(() => {
        // A missing texture only hides it in the preview.
      });
    return () => {
      cancelled = true;
      if (group) {
        group.removeFromParent();
        disposeExtra(group);
      }
    };
  }, [accessory, width, height]);

  // Hat on the head; the pet on the shoulder, beside the head or walking beside the player.
  useEffect(() => {
    const v = viewer.current;
    if (!v || (!hat && !pet)) return;
    let cancelled = false;
    const hatGroup = hat ? buildHat(hat) : null;
    if (hatGroup) v.playerObject.skin.head.add(hatGroup);
    let built: BuiltPet | null = null;
    if (pet) {
      buildPet(pet, v.playerObject.skin.map, slim)
        .then((b) => {
          if (cancelled || !viewer.current) return disposeExtra(b.group);
          built = b;
          b.group.position.set(...PET_PLACE[pet.tier]);
          v.playerObject.skin.add(b.group);
        })
        .catch(() => {
          // A missing texture only hides the pet in the preview.
        });
    }
    // Walking pets stand beside the player: zoom out a little so they fit.
    const zoom = v.zoom;
    if (pet?.tier === "walking") v.zoom = zoom * 0.78;
    let frame = 0;
    const tick = (now: number) => {
      const t = now / 50; // game ticks, like the mod
      if (built && pet) {
        const walking = pet.tier === "walking" && pose === "walk";
        built.update(t, walking ? t * 0.9 : 0, walking ? 1 : 0);
        if (pet.tier === "flying") built.group.position.y = PET_PLACE.flying[1] + Math.sin(t * 0.08) * 1.1;
        // The mini-me wears the same skin as the big player, also once it has loaded.
        if (built.mini && built.mini.skin.map !== v.playerObject.skin.map) built.mini.skin.map = v.playerObject.skin.map;
      }
      if (hatGroup) animateChroma(hatGroup, now);
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
      v.zoom = zoom;
      if (hatGroup) {
        hatGroup.removeFromParent();
        disposeExtra(hatGroup);
      }
      if (built) {
        built.group.removeFromParent();
        // The mini-me shares the viewer's skin texture: keep it alive.
        if (!built.mini) disposeExtra(built.group);
      }
    };
  }, [hat, pet, slim, pose, width, height]);

  return <canvas ref={canvas} className="cursor-grab active:cursor-grabbing" aria-label="3D-Vorschau" />;
}
