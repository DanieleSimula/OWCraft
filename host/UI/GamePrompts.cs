using HarmonyLib;
using OWCraft.Player;
using UnityEngine;

namespace OWCraft.UI
{
	/// <summary>
	/// Our prompts drawn by the game's own PromptManager, so they get its font, size and key icons.
	/// The PromptManager shows any visible prompt in any input mode; it's the game's objects that hide
	/// theirs outside their own modes, which is why Minecraft mode lost them.
	/// </summary>
	public sealed class GamePrompts
	{
		PromptManager _addedTo;
		Prompt _use, _notice;
		string _key; // "<CMD>" with the mouse icon, or a text label when there is no icon

		/// <summary>One of our prompts and what it shows now (to touch the game's UI only on changes).</summary>
		sealed class Prompt
		{
			public ScreenPrompt Screen;
			public string Prefix, Shown;
			public bool Visible;

			public void Show(string text)
			{
				if (text != null && text != Shown)
				{
					Screen.SetText(Prefix + text);
					Shown = text;
				}
				bool visible = text != null;
				if (visible == Visible) return;
				Screen.SetVisibility(visible);
				Visible = visible;
			}
		}

		/// <summary>From LateUpdate: after the game's own Updates have set their prompts.
		/// usable: right click does something (icon shown); otherwise it's a notice ("needs launch codes").</summary>
		public void Show(string text, bool usable)
		{
			var pm = Locator.GetPromptManager();
			if (pm == null) return;
			if (_use == null || _addedTo != pm)
			{
				// A new scene has a new PromptManager: make ours again.
				var icon = RightClickIcon();
				_key = icon != null ? "<CMD> " : "[" + OuterWildsInteractor.RightClickLabel() + "] ";
				_use = new Prompt { Screen = new ScreenPrompt(_key, icon, 0), Prefix = _key };
				_notice = new Prompt { Screen = new ScreenPrompt(" ", 0), Prefix = "" };
				pm.AddScreenPrompt(_use.Screen, PromptPosition.Center, false);
				pm.AddScreenPrompt(_notice.Screen, PromptPosition.Center, false);
				_addedTo = pm;
			}
			_use.Show(usable ? text : null);
			_notice.Show(usable ? null : text);
		}
		/// <summary>The game's own right mouse button picture (ButtonPrompts/Keyboard & Mouse/Keyboard_Black_Mouse_Right).</summary>
		static Sprite RightClickIcon()
		{
			var lib = ButtonPromptLibrary.SharedInstance;
			var tex = lib != null ? lib.GetButtonTexture(KeyCode.Mouse1) : null;
			if (tex == null)
			{
				OWCraft.LogOnce("no-mouse-icon", "right mouse button icon not found, using a text label");
				return null;
			}
			return Sprite.Create(tex, new Rect(0, 0, tex.width, tex.height), new Vector2(0.5f, 0.5f));
		}
	}

	/// <summary>
	/// The launch codes line (bottom left, once you know them): KeyInfoPromptController hides it in any
	/// input mode but its own. In Minecraft mode we set it ourselves and skip the game's Update.
	/// </summary>
	[HarmonyPatch(typeof(KeyInfoPromptController), "Update")]
	static class LaunchCodesPromptPatch
	{
		static bool Prefix(KeyInfoPromptController __instance)
		{
			if (!OWCraft.MinecraftMode) return true;
			var f = Traverse.Create(__instance);
			f.Field("_codePrompt").GetValue<ScreenPrompt>()?.SetVisibility(f.Field("_displayCodePrompt").GetValue<bool>());
			return false;
		}
	}
}
