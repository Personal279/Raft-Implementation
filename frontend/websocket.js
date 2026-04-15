//let socket = new WebSocket("ws://localhost:8080/draw");
//
//socket.onopen = () => {
//console.log("connected to gateway");
//};
//
//socket.onmessage = (msg) => {
//console.log("message from server:", msg.data);
//};
//
//function sendStroke(event){
//socket.send(JSON.stringify(event));
//}


let socket = new WebSocket("ws://localhost:8080/draw");

socket.onopen = () => {
    console.log("connected to gateway");
};

socket.onmessage = (msg) => {
    const event = JSON.parse(msg.data);
    ctx.beginPath();
    ctx.moveTo(event.x1, event.y1);
    ctx.lineTo(event.x2, event.y2);
    ctx.strokeStyle = event.color || "black";
    ctx.lineWidth = 2;
    ctx.stroke();
};

function sendStroke(event) {
    if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(event));
    }
}