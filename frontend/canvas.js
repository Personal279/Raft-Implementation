let canvas = document.getElementById("board");
let ctx = canvas.getContext("2d");

let drawing = false;

canvas.addEventListener("mousedown", () => {
drawing = true;
});

canvas.addEventListener("mouseup", () => {
drawing = false;
});

canvas.addEventListener("mousemove", function(e){

if(!drawing) return;

let rect = canvas.getBoundingClientRect();

let x = e.clientX - rect.left;
let y = e.clientY - rect.top;

ctx.fillRect(x,y,2,2);

sendStroke({
type:"point",
x1:x,
y1:y,
x2:x,
y2:y,
color:"black"
});

});