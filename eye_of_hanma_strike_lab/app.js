const partUrls = ["./parts/app1.txt", "./parts/app2.txt", "./parts/app3.txt"];
try {
  const parts = await Promise.all(partUrls.map(async (url) => {
    const response = await fetch(url, { cache: "no-cache" });
    if (!response.ok) throw new Error(`Failed to load ${url}: ${response.status}`);
    return response.text();
  }));
  const blob = new Blob([parts.join("")], { type: "text/javascript" });
  const moduleUrl = URL.createObjectURL(blob);
  await import(moduleUrl);
  URL.revokeObjectURL(moduleUrl);
} catch (error) {
  console.error(error);
  const guide = document.getElementById("bodyGuide");
  if (guide) guide.textContent = "APP MODULE LOAD ERROR";
  const toast = document.getElementById("toast");
  if (toast) {
    toast.textContent = "Strike Lab failed to load. Refresh while online.";
    toast.classList.add("show");
  }
}
