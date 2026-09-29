const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "hanview-translate" });
    }

    if (request.method !== "POST" || url.pathname !== "/translate") {
      return json({ error: "not_found" }, 404);
    }

    if (!env.OPENAI_API_KEY) {
      return json({ error: "OPENAI_API_KEY is not configured" }, 500);
    }

    let body;
    try { body = await request.json(); }
    catch { return json({ error: "invalid_json" }, 400); }

    const items = Array.isArray(body.items) ? body.items : [];
    const cleanItems = items.slice(0, 80).map((item, index) => ({
      id: Number.isInteger(item?.id) ? item.id : index,
      text: String(item?.text ?? "").slice(0, 3000),
    })).filter((item) => item.text.trim().length > 0);

    if (cleanItems.length === 0) return json({ translations: [] });

    const instructions = [
      "You translate Chinese shopping-app UI text into natural Korean.",
      "Return ONLY valid JSON in this exact shape: {\"translations\":[{\"id\":0,\"text\":\"...\"}]}.",
      "Keep every input id exactly once and in the same order.",
      "Translate naturally, not word-for-word. Chinese e-commerce filler such as 亲/亲亲/宝 should become natural Korean customer-service wording or be omitted when appropriate.",
      "Never alter prices, quantities, dates, measurements, weights, model numbers, coupon conditions, shipping/return/refund restrictions, or other factual constraints.",
      "Keep short UI labels short. For seller chat, use natural polite Korean.",
      "Do not explain the translation and do not use Markdown."
    ].join("\n");

    const openaiResponse = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "authorization": `Bearer ${env.OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: env.OPENAI_MODEL || "gpt-6-luna",
        reasoning: { effort: "none" },
        instructions,
        input: JSON.stringify({ items: cleanItems }),
        max_output_tokens: 5000,
      }),
    });

    const data = await openaiResponse.json();
    if (!openaiResponse.ok) return json({ error: "openai_error", detail: data }, openaiResponse.status);

    const outputText = extractOutputText(data);
    let parsed;
    try { parsed = JSON.parse(stripCodeFence(outputText)); }
    catch { return json({ error: "invalid_model_json", raw: outputText }, 502); }

    const translated = Array.isArray(parsed.translations) ? parsed.translations : [];
    return json({ translations: translated.map((item) => ({ id: Number(item.id), text: String(item.text ?? "") })) });
  },
};

function extractOutputText(response) {
  let text = "";
  for (const item of response.output || []) {
    if (item?.type !== "message") continue;
    for (const part of item.content || []) {
      if (part?.type === "output_text" && typeof part.text === "string") text += part.text;
    }
  }
  return text;
}

function stripCodeFence(value) {
  const trimmed = String(value || "").trim();
  if (!trimmed.startsWith("```")) return trimmed;
  return trimmed.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: JSON_HEADERS });
}
