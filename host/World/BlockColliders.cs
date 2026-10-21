using System.Collections.Generic;
using UnityEngine;

namespace OWCraft.World
{
	/// <summary>
	/// Fork: Minecraft's blocks as real Unity collision, so the Outer Wilds body (owpg, and Steve with
	/// the "Outer Wilds physics" setting) stands on them and can't walk through them. Minecraft sends
	/// each section's collision as boxes (full blocks already merged); they sit under the tile's
	/// anchor, exactly where BlockRenderer draws the section.
	/// </summary>
	public sealed class BlockColliders
	{
		readonly Dictionary<(int, int, int), Section> _sections = new Dictionary<(int, int, int), Section>();
		// Ours: CollisionStreamer must not send them back to Minecraft as planet ground.
		static readonly HashSet<Collider> Ours = new HashSet<Collider>();

		public static bool IsOurs(Collider c) => Ours.Contains(c);
		public int SectionCount => _sections.Count;

		sealed class Section
		{
			public (int x, int y, int z) Key;
			public float[] Boxes; // 6 floats each: min x/y/z, max x/y/z, section-local Minecraft blocks
			public int[] Ramps; // stairs: x | y << 4 | z << 8 | uphill << 12 (0 north, 1 south, 2 west, 3 east)
			public GameObject Go;
		}

		public unsafe void OnShapes(System.IntPtr p, int bytes)
		{
			int* hdr = (int*)p;
			int sx = hdr[0], sy = hdr[1], sz = hdr[2], count = hdr[3];
			var key = (sx, sy, sz);
			if (count <= 0)
			{
				Remove(key);
				return;
			}
			if (bytes < 16 + (long)count * 24) return;
			var boxes = new float[count * 6];
			float* f = (float*)(p + 16);
			for (int i = 0; i < boxes.Length; i++) boxes[i] = f[i];
			int[] ramps = null;
			long rampAt = 16 + (long)count * 24;
			if (bytes >= rampAt + 4)
			{
				int n = *(int*)(p + (int)rampAt);
				if (n > 0 && bytes >= rampAt + 4 + (long)n * 4)
				{
					ramps = new int[n];
					int* r = (int*)(p + (int)rampAt + 4);
					for (int i = 0; i < n; i++) ramps[i] = r[i];
				}
			}
			if (!_sections.TryGetValue(key, out var section))
			{
				section = new Section { Key = key };
				_sections[key] = section;
			}
			section.Boxes = boxes;
			section.Ramps = ramps;
			Place(section);
		}

		void Place(Section section)
		{
			Destroy(section);
			var (sx, sy, sz) = section.Key;
			var anchor = AnchorGrid.AnchorForMc(sx * 16 + 8, sz * 16 + 8);
			if (anchor == null || section.Boxes == null) return;
			var go = new GameObject($"OWCraft collision {sx},{sy},{sz}");
			go.transform.SetParent(anchor.EnsureRoot(), false);
			// Same origin as the section's mesh (BlockRenderer.Place); Z flipped into Unity's space.
			go.transform.localPosition = new Vector3(sx * 16 - anchor.McX0, sy * 16 - AnchorGrid.Y0, -(sz * 16 - anchor.McZ0));
			go.transform.localRotation = Quaternion.identity;
			go.transform.localScale = Vector3.one;
			var b = section.Boxes;
			for (int i = 0; i + 5 < b.Length; i += 6)
			{
				var box = go.AddComponent<BoxCollider>();
				box.center = new Vector3((b[i] + b[i + 3]) * 0.5f, (b[i + 1] + b[i + 4]) * 0.5f, -(b[i + 2] + b[i + 5]) * 0.5f);
				box.size = new Vector3(b[i + 3] - b[i], b[i + 4] - b[i + 1], b[i + 5] - b[i + 2]);
				Ours.Add(box);
			}
			if (section.Ramps != null)
				foreach (int ramp in section.Ramps) AddRamp(go.transform, ramp);
			section.Go = go;
		}

		static readonly Vector3[] Uphill = { Vector3.forward, Vector3.back, Vector3.left, Vector3.right }; // Minecraft's Z is flipped
		const float RampThickness = 0.1f;

		/// <summary>
		/// A ramp over a stair, along the line of the steps' front edges (45 degrees): from the floor half
		/// a block in front of the stair up to the top of its back half. Stairs in a row make one smooth
		/// slope, and the Outer Wilds body walks up it like any slope of the game.
		/// </summary>
		static void AddRamp(Transform section, int packed)
		{
			int x = packed & 15, y = (packed >> 4) & 15, z = (packed >> 8) & 15, uphill = (packed >> 12) & 3;
			Vector3 dir = Uphill[uphill];
			Vector3 along = (dir + Vector3.up).normalized; // up the slope
			Vector3 normal = (Vector3.up - dir).normalized; // out of the slope's top face
			var go = new GameObject("OWCraft stair ramp");
			go.transform.SetParent(section, false);
			// Centred on the stair's front face at half height; the top face lies on the slope.
			go.transform.localPosition = new Vector3(x + 0.5f, y + 0.5f, -(z + 0.5f)) - dir * 0.5f - normal * (RampThickness * 0.5f);
			go.transform.localRotation = Quaternion.LookRotation(along, normal);
			var box = go.AddComponent<BoxCollider>();
			box.size = new Vector3(1f, RampThickness, Mathf.Sqrt(2f));
			Ours.Add(box);
		}

		static void Destroy(Section section)
		{
			if (section.Go == null) return;
			foreach (var c in section.Go.GetComponentsInChildren<Collider>()) Ours.Remove(c);
			Object.Destroy(section.Go);
			section.Go = null;
		}

		void Remove((int, int, int) key)
		{
			if (!_sections.TryGetValue(key, out var section)) return;
			_sections.Remove(key);
			Destroy(section);
		}

		/// <summary>After a scene load: the old objects went with the scene; build them again.</summary>
		public void Rebuild()
		{
			Ours.Clear();
			foreach (var section in _sections.Values)
			{
				section.Go = null;
				Place(section);
			}
		}

		public void Clear()
		{
			foreach (var section in _sections.Values) Destroy(section);
			_sections.Clear();
			Ours.Clear();
		}
	}
}
