(() => {
  const links = Array.from(document.querySelectorAll("[data-view]"));
  const views = Array.from(document.querySelectorAll(".view"));
  const title = document.getElementById("currentSection");
  const sidebar = document.getElementById("sidebar");
  const names = {overview:"Overview",nearby:"Nearby network",identity:"My identity",account:"LINKA account",calls:"Calling",mesh:"Mesh network",about:"About LINKA"};
  function showView(id) {
    if (!names[id]) id = "overview";
    views.forEach(view => view.classList.toggle("active", view.id === id));
    links.forEach(link => link.classList.toggle("active", link.dataset.view === id));
    title.textContent = names[id];
    history.replaceState(null, "", "#" + id);
    sidebar.classList.remove("open");
    window.scrollTo({top:0,behavior:"smooth"});
  }
  links.forEach(link => link.addEventListener("click", event => {
    event.preventDefault();
    showView(link.dataset.view);
  }));
  document.getElementById("menuButton").addEventListener("click", () => sidebar.classList.toggle("open"));
  document.getElementById("year").textContent = new Date().getFullYear();
  showView(location.hash.slice(1) || "overview");
})();