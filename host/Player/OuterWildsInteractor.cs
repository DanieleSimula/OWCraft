using System;
using System.Reflection;
using HarmonyLib;
using UnityEngine;

namespace OWCraft.Player
{
	/// <summary>
	/// Right click on Outer Wilds things (ship seat, elevator, switches) while in Minecraft mode.
	/// The game's own interaction doesn't run then (input mode None), so this does what
	/// FirstPersonManipulator.LateUpdate and InteractReceiver.Observe do: a ray from the camera on
	/// OWLayerMask.blockableInteractMask, an InteractReceiver on the hit collider, inside its range
	/// and view angle; or the InteractZone the player stands in. Then it fires its OnPressInteract,
	/// like pressing the interact key.
	/// </summary>
	public static class OuterWildsInteractor
	{
		const float RayLength = 75f; // the game's own

		static readonly FieldInfo PressEvent = AccessTools.Field(typeof(SingleInteractionVolume), "OnPressInteract");

		/// <summary>What the game would let you use right now, or null: the InteractReceiver under the
		/// crosshair, otherwise the InteractZone you stand in (elevator buttons and the like are zones:
		/// you stand inside a trigger and look the right way). With explain, logs why (on a right click).</summary>
		public static SingleInteractionVolume Find(bool explain = false)
		{
			var cam = Locator.GetPlayerCamera();
			if (cam == null) return null;
			var t = cam.transform;
			string rayNote;
			if (Physics.Raycast(t.position, t.forward, out RaycastHit hit, RayLength, OWLayerMask.blockableInteractMask))
			{
				var r = hit.collider.GetComponent<InteractReceiver>();
				string what = $"{hit.collider.name} (layer {LayerMask.LayerToName(hit.collider.gameObject.layer)}, trigger {hit.collider.isTrigger}) at {hit.distance:F2} m";
				if (r != null)
				{
					string why = WhyNot(r, hit.distance, t);
					if (why == null) return r;
					if (explain) OWCraft.Log($"right click: hit {what}, receiver {r.name}: {why}");
					return null; // the game too stops at the first receiver
				}
				// A switch can sit inside or just behind the solid model the ray hits (elevatorswitch_COLLIDER).
				var near = NearbyReceiver(hit.point, t, out string nearNote);
				if (near != null) return near;
				rayNote = $"hit {what}, no InteractReceiver on it ({nearNote})";
			}
			else rayNote = "the ray hit nothing";

			var zone = FindZone(t, explain, out string zoneNote);
			if (zone == null && explain) OWCraft.Log($"right click: {rayNote}; {zoneNote}");
			return zone;
		}

		static string WhyNot(InteractReceiver r, float distance, Transform cam)
		{
			// Not r.enabled: InteractReceiver.Start always disables the component (the manipulator drives
			// it); the game turns an interaction off by deactivating its collider, so the ray misses it.
			var f = Traverse.Create(r);
			float range = f.Field("_interactRange").GetValue<float>();
			if (distance > range) return $"too far, range {range:F2} m";
			if (PlayerState.IsInsideShip() && !f.Field("_usableInShip").GetValue<bool>()) return "not usable inside the ship";
			if (f.Field("_checkViewAngle").GetValue<bool>())
			{
				float angle = Vector3.Angle(r.transform.forward, cam.position - r.transform.position);
				float max = f.Field("_maxViewAngle").GetValue<float>();
				if (angle > max) return $"view angle {angle:F0} over {max:F0}";
			}
			return null;
		}

		/// <summary>A usable receiver within half a metre of where the ray stopped.</summary>
		static InteractReceiver NearbyReceiver(Vector3 point, Transform cam, out string note)
		{
			note = "no receiver nearby";
			foreach (var c in Physics.OverlapSphere(point, 0.6f, ~0, QueryTriggerInteraction.Collide))
			{
				var r = c.GetComponent<InteractReceiver>();
				if (r == null) continue;
				float d = Vector3.Distance(cam.position, c.bounds.ClosestPoint(cam.position));
				string why = WhyNot(r, d, cam);
				if (why == null) return r;
				note = $"receiver {r.name} nearby: {why}";
			}
			return null;
		}

		static InteractZone[] _zones; // every active zone in the scene
		static float _zonesAt;

		public static void ResetScene()
		{
			_zones = null;
			_keyInfo = null;
			_uplink = null;
			_beamSwitch = null;
			_searchedAt = -100f;
		}

		// ---- things the game only does for its own character -------------------------------------

		static KeyInfoPromptController _keyInfo; // shows "launch codes" bottom left
		static MemoryUplinkTrigger _uplink; // the observatory statue
		static ShipTractorBeamSwitch _beamSwitch; // the ship's tractor beam
		static float _searchedAt = -100f;

		/// <summary>Found also when disabled or inactive (FindObjectsOfType would skip them).</summary>
		static T FindInScene<T>() where T : Component
		{
			foreach (var c in Resources.FindObjectsOfTypeAll<T>())
				if (c.gameObject.scene.IsValid()) return c;
			return null;
		}

		static void FindSceneObjects()
		{
			if ((_keyInfo != null && _uplink != null && _beamSwitch != null) || Time.unscaledTime < _searchedAt + 5f) return;
			_searchedAt = Time.unscaledTime;
			if (_keyInfo == null) _keyInfo = FindInScene<KeyInfoPromptController>();
			if (_uplink == null) _uplink = FindInScene<MemoryUplinkTrigger>();
			if (_beamSwitch == null) _beamSwitch = FindInScene<ShipTractorBeamSwitch>();
		}

		/// <summary>
		/// The observatory statue wants the player: launch codes known, standing in its trigger (or the
		/// game already saw that). Its sequence locks the camera and ends the loop, so it needs the
		/// game's own character.
		/// </summary>
		public static MemoryUplinkTrigger StatueWaiting()
		{
			FindSceneObjects();
			if (_uplink == null || !_uplink.gameObject.activeInHierarchy || !PlayerData.KnowsLaunchCodes()) return null;
			var f = Traverse.Create(_uplink);
			if (f.Field("_hasSimulationResumed").GetValue<bool>()) return null;
			if (f.Field("_waitForPlayerGrounded").GetValue<bool>()) return _uplink;
			var col = _uplink.GetComponent<Collider>();
			var body = Locator.GetPlayerBody();
			if (col == null || !col.enabled || body == null) return null;
			return Inside(col, body.transform.position) ? _uplink : null;
		}

		/// <summary>
		/// The player stands in the ship's tractor beam while it's on (outside the ship). The beam pulls
		/// the body with the game's physics, which can't move a body that Minecraft places: the Outer
		/// Wilds character has to take over to be lifted in.
		/// </summary>
		public static bool InActiveTractorBeam()
		{
			FindSceneObjects();
			if (_beamSwitch == null) return false;
			var fluid = Traverse.Create(_beamSwitch).Field("_beamFluid").GetValue<TractorBeamFluid>();
			var body = Locator.GetPlayerBody();
			var cam = Locator.GetPlayerCamera();
			if (fluid == null || !fluid.IsVolumeActive() || body == null) return false;
			return InBeam(fluid, body.transform.position, cam != null ? cam.transform.position : body.transform.position);
		}

		/// <summary>Inside the beam's volume, whether it's on or off.</summary>
		static bool InBeam(TractorBeamFluid fluid, Vector3 p, Vector3 eye)
		{
			var shape = fluid.GetOWTriggerVolume()?.GetShape();
			if (shape != null) return shape.PointInside(p) || shape.PointInside(eye);
			var col = fluid.GetComponent<Collider>();
			return col != null && (Inside(col, p) || Inside(col, eye));
		}

		/// <summary>
		/// The beam goes off when you enter the ship and back on in ShipTractorBeamSwitch.OnTriggerExit,
		/// when the player's detector leaves the switch's trigger. Minecraft mode turns that collider off
		/// near the ship, and Unity sends no exit for a disabled collider: the beam stayed off. As Steve,
		/// once outside that trigger, do what OnTriggerExit does.
		/// </summary>
		public static void RearmTractorBeam()
		{
			FindSceneObjects();
			if (_beamSwitch == null) return;
			var f = Traverse.Create(_beamSwitch);
			var fluid = f.Field("_beamFluid").GetValue<TractorBeamFluid>();
			if (fluid == null || fluid.IsVolumeActive()) return;
			if (f.Field("_isPlayerInShip").GetValue<bool>() || !f.Field("_functional").GetValue<bool>()) return;
			var col = _beamSwitch.GetComponent<Collider>();
			var body = Locator.GetPlayerBody();
			if (col == null || body == null || Inside(col, body.transform.position)) return;
			// Not while still standing where the beam would grab you again.
			var cam = Locator.GetPlayerCamera();
			if (InBeam(fluid, body.transform.position, cam != null ? cam.transform.position : body.transform.position)) return;
			_beamSwitch.ActivateTractorBeam();
		}

		/// <summary>What the statue's OnTriggerEnter does when the player's detector walks in.</summary>
		public static void TriggerStatue(MemoryUplinkTrigger statue)
		{
			if (Traverse.Create(statue).Field("_waitForPlayerGrounded").GetValue<bool>()) return; // the game saw it
			var detector = Locator.GetPlayerDetector();
			var enter = AccessTools.Method(typeof(MemoryUplinkTrigger), "OnTriggerEnter");
			if (detector == null || enter == null) return;
			var arg = detector.GetComponent(enter.GetParameters()[0].ParameterType);
			if (arg == null) return;
			OWCraft.Log("observatory statue: launch codes known, starting its sequence as the Outer Wilds character");
			enter.Invoke(statue, new object[] { arg });
		}

		/// <summary>The zone the player is in, if looking within its viewing window.</summary>
		static InteractZone FindZone(Transform cam, bool explain, out string note)
		{
			note = "not inside any interact zone";
			// Our own inside test, not FirstPersonManipulator._interactZone: its trigger events may not
			// follow a body that Minecraft places, and a stale zone would work from anywhere.
			// FindObjectsOfType skips inactive objects (sectors load parts of planets later): refresh now and then.
			if (_zones == null || Time.unscaledTime > _zonesAt + 5f)
			{
				_zones = UnityEngine.Object.FindObjectsOfType<InteractZone>();
				_zonesAt = Time.unscaledTime;
			}
			var body = Locator.GetPlayerBody();
			Vector3 feet = body != null ? body.transform.position : cam.position;
			var detector = Locator.GetPlayerDetector();
			InteractZone zone = null;
			float nearest = float.MaxValue;
			string nearestName = null;
			foreach (var z in _zones)
			{
				if (z == null || !z.gameObject.activeInHierarchy) continue;
				if (InZone(z, cam.position, feet, detector))
				{
					zone = z;
					break;
				}
				float d = Vector3.Distance(z.transform.position, feet);
				if (d < nearest)
				{
					nearest = d;
					nearestName = z.name;
				}
			}
			if (zone == null && nearestName != null) note += $" (nearest {nearestName}, {nearest:F1} m)";
			if (zone == null) return null;
			float window = Traverse.Create(zone).Field("_viewingWindow").GetValue<float>();
			float angle = Vector3.Angle(cam.forward, zone.transform.forward);
			if (angle > window)
			{
				note = $"in zone {zone.name}, but looking {angle:F0} degrees off (window {window:F0})";
				return null;
			}
			return zone;
		}

		/// <summary>
		/// Zones are OWTriggerVolumes, often with the game's own Shape (box, sphere...) instead of a
		/// Unity collider: ask the trigger whether it already tracks the player, then the shape, then a collider.
		/// </summary>
		static bool InZone(InteractZone z, Vector3 cam, Vector3 feet, GameObject detector)
		{
			var trigger = Traverse.Create(z).Field("_trigger").GetValue<OWTriggerVolume>();
			if (trigger != null)
			{
				var shape = trigger.GetShape();
				if (shape != null) return shape.active && (shape.PointInside(cam) || shape.PointInside(feet));
				// Tracking alone can be stale (the body is placed by Minecraft, exits may be missed): only nearby.
				if (detector != null && trigger.IsTrackingObject(detector) && Vector3.Distance(z.transform.position, feet) < 4f) return true;
			}
			var col = z.GetComponent<Collider>();
			return col != null && col.enabled && (Inside(col, cam) || Inside(col, feet));
		}

		static bool Inside(Collider col, Vector3 p)
		{
			if (!col.bounds.Contains(p)) return false;
			// ClosestPoint is exact for primitives and convex meshes; a concave mesh only has its bounds.
			if (col is MeshCollider mc && !mc.convex) return true;
			return (col.ClosestPoint(p) - p).sqrMagnitude < 1e-6f;
		}

		/// <summary>The game's own text for it ("Sit", "Use"...), in the game's language, without the key icon.</summary>
		public static string PromptText(SingleInteractionVolume r)
		{
			var prompt = Traverse.Create(r).Field("_noCommandIconPrompt").GetValue<ScreenPrompt>();
			string text = prompt?.GetText();
			return string.IsNullOrEmpty(text) ? null : text;
		}

		/// <summary>"Right click" in the game's language (Italian or English).</summary>
		public static string RightClickLabel()
		{
			var tt = TextTranslation.Get();
			return tt != null && tt.GetLanguage() == TextTranslation.Language.ITALIAN ? "Tasto destro" : "Right click";
		}

		/// <summary>
		/// False for a notice like "Requires launch codes": the game then shows its text without the
		/// key icon (SetKeyCommandVisible(false)) and pressing does nothing.
		/// </summary>
		public static bool HasCommand(SingleInteractionVolume v) =>
			Traverse.Create(v).Field("_usingPromptWithCommand").GetValue<bool>();

		/// <summary>
		/// The game's own prompt for whatever its manipulator last focused can stay on screen once
		/// Minecraft mode takes the controls (it never sees the player look away): drop that focus.
		/// </summary>
		public static void DropGameFocus()
		{
			var cam = Locator.GetPlayerCamera();
			var fpm = cam != null ? cam.GetComponent<FirstPersonManipulator>() : null;
			if (fpm == null) return;
			var f = Traverse.Create(fpm);
			f.Field("_interactReceiver").GetValue<InteractReceiver>()?.LoseFocus();
			f.Field("_interactZone").GetValue<InteractZone>()?.LoseFocus();
		}

		/// <summary>Same as pressing the interact key while looking at it.</summary>
		public static void Press(SingleInteractionVolume r)
		{
			var handlers = PressEvent?.GetValue(r) as Delegate;
			if (handlers == null)
			{
				OWCraft.LogOnce("no-press-" + r.name, $"{r.name}: nothing listens to its interact press");
				return;
			}
			OWCraft.Log($"using {r.name} (Outer Wilds)");
			handlers.DynamicInvoke();
		}
	}
}
