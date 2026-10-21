using System.Collections.Generic;
using System.Reflection.Emit;
using HarmonyLib;
using UnityEngine;
using UnityEngine.InputSystem;
using OWCraft.World;

namespace OWCraft.Player
{
	/// <summary>
	/// "Outer Wilds physics" (fork): the Outer Wilds controller moves Steve. The game's input stays off
	/// (Minecraft has the keyboard), so its controller gets WASD and space from here instead.
	/// Minecraft's default keys; a remapped Minecraft would still walk with these.
	/// </summary>
	static class DrivenInput
	{
		public static Vector2 Move()
		{
			var kb = Keyboard.current;
			if (kb == null || PlayerDriver.ScreenOpen) return Vector2.zero;
			var v = new Vector2(
				(kb[Key.D].isPressed ? 1f : 0f) - (kb[Key.A].isPressed ? 1f : 0f),
				(kb[Key.W].isPressed ? 1f : 0f) - (kb[Key.S].isPressed ? 1f : 0f));
			return v.sqrMagnitude > 1f ? v.normalized : v;
		}

		public static bool Jump()
		{
			var kb = Keyboard.current;
			return kb != null && !PlayerDriver.ScreenOpen && kb[Key.Space].isPressed;
		}
	}

	/// <summary>The controller's walking input (moveXZ) while Steve drives the body.</summary>
	[HarmonyPatch(typeof(OWInput), nameof(OWInput.GetAxisValue))]
	static class DrivenMovePatch
	{
		static bool Prefix(IInputCommands __0, ref Vector2 __result)
		{
			if (!PlayerDriver.DrivenNow || __0 != InputLibrary.moveXZ) return true;
			// Gliding: Minecraft's elytra doesn't take WASD, only where you look.
			__result = PlayerDriver.GlidingNow ? Vector2.zero : SneakEdge.Hold(DrivenInput.Move());
			return false;
		}
	}

	/// <summary>
	/// Minecraft's jump: holding space jumps again on landing, no charge-up. The height comes from the
	/// jump speeds PlayerDriver sets.
	/// </summary>
	[HarmonyPatch(typeof(PlayerCharacterController), "UpdateJumpInput")]
	static class DrivenJumpPatch
	{
		static readonly AccessTools.FieldRef<PlayerCharacterController, bool> Grounded = AccessTools.FieldRefAccess<PlayerCharacterController, bool>("_isGrounded");
		static readonly AccessTools.FieldRef<PlayerCharacterController, bool> JumpNext = AccessTools.FieldRefAccess<PlayerCharacterController, bool>("_jumpNextFixedUpdate");
		static readonly AccessTools.FieldRef<PlayerCharacterController, float> Charge = AccessTools.FieldRefAccess<PlayerCharacterController, float>("_jumpChargeTime");
		static readonly AccessTools.FieldRef<PlayerCharacterController, float> LastJump = AccessTools.FieldRefAccess<PlayerCharacterController, float>("_lastJumpTime");

		static bool Prefix(PlayerCharacterController __instance)
		{
			if (!PlayerDriver.DrivenNow) return true;
			Charge(__instance) = 0f;
			if (DrivenInput.Jump() && Grounded(__instance) && Time.time - LastJump(__instance) > 0.25f && !PlayerDriver.FlyingNow && !PlayerDriver.GlidingNow)
			{
				JumpNext(__instance) = true;
				PlayerDriver.NoteJump(__instance);
			}
			return false;
		}
	}

	/// <summary>
	/// Falls and bumps hurt in Minecraft (it works out fall damage from the moves we send); Outer Wilds'
	/// own impact damage would hurt twice.
	/// </summary>
	[HarmonyPatch(typeof(PlayerResources), "OnImpact")]
	static class DrivenImpactPatch
	{
		static bool Prefix() => !PlayerDriver.DrivenNow;
	}

	/// <summary>
	/// The controller decides "standing on something" with a sphere 0.46 m wide in radius, fixed in its
	/// code. With Steve's 0.3 m body that sphere still touched a block's edge after the body had left
	/// it: the player clung to edges and had to push to fall off. While Steve drives, the sphere is his
	/// width; otherwise the game's own 0.46.
	/// </summary>
	[HarmonyPatch(typeof(PlayerCharacterController), "CastForGrounded")]
	static class DrivenGroundCastPatch
	{
		public static int Replaced; // diagnostics: how many 0.46s were swapped (1 expected)

		static float Radius() => PlayerDriver.DrivenNow ? Mathf.Max(0.05f, PlayerDriver.BodyRadius - 0.02f) : 0.46f;

		static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code)
		{
			var radius = AccessTools.Method(typeof(DrivenGroundCastPatch), nameof(Radius));
			foreach (var ci in code)
			{
				if (ci.opcode == OpCodes.Ldc_R4 && ci.operand is float f && Mathf.Abs(f - 0.46f) < 0.0001f)
				{
					ci.opcode = OpCodes.Call; // keeps the instruction's labels
					ci.operand = radius;
					Replaced++;
				}
				yield return ci;
			}
		}
	}

	/// <summary>
	/// Minecraft's sneaking: you don't walk off an edge. Each direction of the input (sideways, forward)
	/// is kept only if, a little further that way, some part of Steve's 0.6 m footprint still has
	/// ground under it within a step; otherwise that direction stops, and the other may still slide
	/// along the edge, like Minecraft does it axis by axis.
	/// </summary>
	static class SneakEdge
	{
		const float Ahead = 0.1f; // metres tested past where we are
		const float Drop = 0.6f; // more than this below the feet is an edge

		public static Vector2 Hold(Vector2 input)
		{
			if (!PlayerDriver.SneakingNow || input == Vector2.zero) return input;
			var controller = Locator.GetPlayerController();
			var body = Locator.GetPlayerBody();
			if (controller == null || body == null || !controller.IsGrounded()) return input;
			Transform t = body.transform;
			Vector3 feet = t.position - t.up * PlayerDriver.FeetOffset;
			if (input.x != 0f && !Supported(feet + t.right * (Mathf.Sign(input.x) * Ahead), t)) input.x = 0f;
			if (input.y != 0f && !Supported(feet + t.forward * (Mathf.Sign(input.y) * Ahead), t)) input.y = 0f;
			return input;
		}

		static bool Supported(Vector3 feet, Transform t)
		{
			float r = PlayerDriver.BodyRadius - 0.02f;
			Vector3 up = t.up;
			for (int i = 0; i < 5; i++)
			{
				Vector3 at = feet;
				if (i == 1) at += t.right * r;
				else if (i == 2) at -= t.right * r;
				else if (i == 3) at += t.forward * r;
				else if (i == 4) at -= t.forward * r;
				if (Physics.Raycast(at + up * 0.1f, -up, Drop + 0.1f, OWLayerMask.physicalMask, QueryTriggerInteraction.Ignore)) return true;
			}
			return false;
		}
	}

	/// <summary>
	/// Minecraft's ladders and liquids, after the controller's physics step. On a ladder or vine: walking
	/// into it (or holding jump) climbs, sneaking holds on, otherwise you slide down slowly. In water:
	/// jump swims up, sneak dives, otherwise you sink slowly and everything is slowed. Lava, thicker.
	/// Vertical speeds are against what we stand on (the planet turns, ships move).
	/// </summary>
	[HarmonyPatch(typeof(PlayerCharacterController), "FixedUpdate")]
	static class DrivenClimbSwimPatch
	{
		const float ClimbUp = 2.35f, ClimbDown = 3f; // m/s, Minecraft's 0.1176 and 0.15 blocks per tick
		const float SwimUp = 2f, Dive = 2f, Sink = 0.6f; // m/s

		static void Postfix(PlayerCharacterController __instance)
		{
			if (!PlayerDriver.DrivenNow) return;
			bool climbing = PlayerDriver.ClimbingNow, water = PlayerDriver.InWaterNow, lava = PlayerDriver.InLavaNow;
			if (!climbing && !water && !lava) return;
			var rb = Locator.GetPlayerBody();
			if (rb == null) return;
			Vector3 up = rb.transform.up;
			Vector3 v = rb.GetVelocity() - PlayerDriver.GroundVelocity;
			float vertical = Vector3.Dot(v, up);
			Vector3 across = v - up * vertical;
			bool jump = DrivenInput.Jump();
			float target;
			if (climbing)
			{
				bool pushing = DrivenInput.Move().y > 0.1f;
				if (pushing || jump) target = ClimbUp;
				else if (PlayerDriver.SneakingNow) target = 0f;
				else target = Mathf.Max(vertical, -ClimbDown);
				vertical = target;
			}
			else
			{
				float thick = lava ? 0.5f : 1f;
				target = jump ? SwimUp * thick : PlayerDriver.SneakingNow ? -Dive * thick : -Sink * thick;
				vertical = Mathf.Lerp(vertical, target, 0.15f); // water pushes back: eases toward the target speed
				across *= lava ? 0.85f : 0.92f; // drag
			}
			rb.SetVelocity(PlayerDriver.GroundVelocity + across + up * vertical);
		}
	}

	/// <summary>
	/// Creative flight and the elytra, after the controller's physics step. Flying: WASD at Minecraft's
	/// flying speed (double when sprinting), jump up, sneak down, no gravity, as Minecraft. Gliding:
	/// Minecraft's elytra (LivingEntity.travelFallFlying) step by step, with the planet's gravity in
	/// place of Minecraft's: pitch down to dive and gain speed, pitch up to trade it for height.
	/// Speeds are against what we last stood on.
	/// </summary>
	[HarmonyPatch(typeof(PlayerCharacterController), "FixedUpdate")]
	static class DrivenFlyPatch
	{
		const float FlySpeed = 10.9f, FlyUp = 7.5f; // m/s, Minecraft's creative flight
		static Vector3 _last; // the speed we set last step (against the ground)
		static bool _haveLast;

		static void Postfix()
		{
			if (!PlayerDriver.DrivenNow || (!PlayerDriver.FlyingNow && !PlayerDriver.GlidingNow))
			{
				_haveLast = false;
				return;
			}
			var rb = Locator.GetPlayerBody();
			var cam = Locator.GetPlayerCamera();
			if (rb == null) return;
			Transform t = rb.transform;
			Vector3 up = t.up;
			Vector3 ground = PlayerDriver.GroundVelocity;
			Vector3 v = rb.GetVelocity() - ground;
			if (PlayerDriver.FlyingNow)
			{
				// The game's gravity since our last step: take back exactly what pulled us down. Not the
				// measured gravity: it can be a few m/s² off the real pull here, and hovering sank slowly.
				// Only downward: a floor or a ceiling stopping us stays.
				if (_haveLast)
				{
					float down = Vector3.Dot(v - _last, up);
					if (down < 0f) v -= up * down;
				}
				Vector2 input = DrivenInput.Move();
				float speed = FlySpeed * (PlayerDriver.SprintingNow ? 2f : 1f);
				Vector3 target = (t.right * input.x + t.forward * input.y) * speed;
				if (DrivenInput.Jump()) target += up * FlyUp;
				else if (PlayerDriver.SneakingNow) target -= up * FlyUp;
				v = Vector3.Lerp(v, target, 0.25f); // Minecraft's flight eases in and out too
			}
			else if (cam != null)
			{
				v += up * PlayerDriver.FeltGravity * Time.fixedDeltaTime; // the game's gravity this step: ours replaces it
				v = Glide(v, cam.transform.forward, up, PlayerDriver.FeltGravity, Time.fixedDeltaTime);
				if (PlayerDriver.RocketNow) v = Rocket(v, cam.transform.forward, Time.fixedDeltaTime);
			}
			rb.SetVelocity(ground + v);
			_last = v;
			_haveLast = PlayerDriver.FlyingNow;
		}

		/// <summary>
		/// One physics step of a firework rocket's pull, Minecraft's formula (per tick, blocks per tick):
		/// motion += look * 0.1 + (look * 1.5 - motion) * 0.5. Toward 1.5 blocks a tick (30 m/s) along the look.
		/// </summary>
		static Vector3 Rocket(Vector3 v, Vector3 look, float dt)
		{
			float ticks = dt * 20f;
			Vector3 m = v / 20f;
			Vector3 d = look * 0.1f + (look * 1.5f - m) * 0.5f;
			m += d * Mathf.Min(1f, ticks);
			return m * 20f;
		}

		/// <summary>One physics step of Minecraft's elytra, in m/s; Minecraft's tick is 1/20 s.</summary>
		static Vector3 Glide(Vector3 v, Vector3 look, Vector3 up, float g, float dt)
		{
			float ticks = dt * 20f;
			// Minecraft's units: blocks per tick, gravity per tick (0.08 there; the planet's here).
			Vector3 m = v / 20f;
			float grav = g / 400f;
			float lookUp = Vector3.Dot(look, up);
			Vector3 lookFlat = look - up * lookUp;
			float horiz = lookFlat.magnitude; // cos(pitch)
			float pitchUp = Mathf.Asin(Mathf.Clamp(lookUp, -1f, 1f)); // Minecraft's xRot is -pitchUp
			float vy = Vector3.Dot(m, up);
			Vector3 mFlat = m - up * vy;
			float hv = mFlat.magnitude;
			float lift = horiz * horiz; // cos(pitch)^2
			Vector3 dir = horiz > 0.0001f ? lookFlat / horiz : Vector3.zero;

			vy += grav * (-1f + lift * 0.75f) * ticks;
			if (vy < 0f && horiz > 0f)
			{
				float d = vy * -0.1f * lift * ticks; // falling turns into forward speed
				mFlat += dir * d;
				vy += d;
			}
			if (pitchUp > 0f && horiz > 0f)
			{
				float d = hv * Mathf.Sin(pitchUp) * 0.04f * ticks; // climbing costs forward speed
				mFlat -= dir * d;
				vy += d * 3.2f;
			}
			if (horiz > 0f)
				mFlat += (dir * mFlat.magnitude - mFlat) * Mathf.Min(1f, 0.1f * ticks); // turn toward the look
			mFlat *= Mathf.Pow(0.99f, ticks);
			vy *= Mathf.Pow(0.98f, ticks);
			return (mFlat + up * vy) * 20f;
		}
	}
}
