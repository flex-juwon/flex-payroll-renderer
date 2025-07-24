import React from 'react';
import logo from './logo.svg';
import './App.css';

interface HelloCommand {
  name: String
}

const App = (command: HelloCommand) => {
  return (
    <div className="App">
      <header className="App-header">
        <img src={logo} className="App-logo" alt="logo" />
        <p>
          Hello { command ? command.name : 'world' }
        </p>
      </header>
    </div>
  );
}

export default App;
