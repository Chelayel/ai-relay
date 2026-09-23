# Changelog

## 1.8.1

Gemini is the default agent.

## 1.8.0

The agent picker is a dropdown again, beside the model picker. On an Apigee
gateway the model picker offers only the configured model.

## 1.7.0

Slash commands, @file and #skill autocomplete in the composer; queued
messages and draft memory; grouped tool calls; open-at-line from diffs and
tool rows; Copy and Apply on code fences; permission prompts show the command
or edit; a coloured agent switch with a health dot; history search; a setup
card for an unconfigured agent; light-theme accents.

## 1.6.0

Edits shown as diffs with Revert; earlier conversations listed and
resumable; a model picker and, for Claude, a context and cost meter; agent
personas from .claude/agents; the mode dropdown no longer restarts the
conversation; a clock on the running tool and a summary line per turn.

## 1.5.0

Drop files onto the panel or paste an image into the message box: images go
to Gemini and Claude as pictures, text files go in with their contents,
explorer drags attach by path.

## 1.4.0

A + menu in the composer: attach files, attach a skill (SKILL.md files in
.claude/skills and friends), open the MCP config, settings. The panel is
coloured by the agent in use and always shows the one actually running. The
demo backend is gone from the picker.

## 1.3.0

The header shows the workspace folders and the configured MCP servers; the
context chip keeps the file name whole.

## 1.2.0

Live editor context: the chip under the message box follows the current file
and selection and is attached automatically; click it to leave it out.

## 1.0.0

First release: chat view over the `airelay` command with Claude, Gemini and
Copilot backends, streaming tool activity, permission prompts, editor selection
as context.
