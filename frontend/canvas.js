//let canvas = document.getElementById("board");
//let ctx = canvas.getContext("2d");
//
//let drawing = false;
//
//canvas.addEventListener("mousedown", () => {
//drawing = true;
//});
//
//canvas.addEventListener("mouseup", () => {
//drawing = false;
//});
//
//canvas.addEventListener("mousemove", function(e){
//
//if(!drawing) return;
//
//let rect = canvas.getBoundingClientRect();
//
//let x = e.clientX - rect.left;
//let y = e.clientY - rect.top;
//
//ctx.fillRect(x,y,2,2);
//
//sendStroke({
//type:"point",
//x1:x,
//y1:y,
//x2:x,
//y2:y,
//color:"black"
//});
//
//});


let canvas = document.getElementById("board");
let ctx = canvas.getContext("2d");
let drawing = false;
let lastX = 0, lastY = 0;

function resizeCanvas() {
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width;
    canvas.height = rect.height;
}
window.addEventListener("resize", resizeCanvas);
resizeCanvas();

canvas.addEventListener("mousedown", (e) => {
    drawing = true;
    const rect = canvas.getBoundingClientRect();
    lastX = e.clientX - rect.left;
    lastY = e.clientY - rect.top;
});

canvas.addEventListener("mouseup", () => drawing = false);
canvas.addEventListener("mouseleave", () => drawing = false);

canvas.addEventListener("mousemove", (e) => {
    if (!drawing) return;
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    ctx.beginPath();
    ctx.moveTo(lastX, lastY);
    ctx.lineTo(x, y);
    ctx.strokeStyle = "black";
    ctx.lineWidth = 2;
    ctx.stroke();

    sendStroke({ type: "line", x1: lastX, y1: lastY, x2: x, y2: y, color: "black" });
    lastX = x;
    lastY = y;
});