let socket = new WebSocket("ws://localhost:8080/draw");

socket.onopen = () => {
console.log("connected to gateway");
};

socket.onmessage = (msg) => {
console.log("message from server:", msg.data);
};

function sendStroke(event){
socket.send(JSON.stringify(event));
}