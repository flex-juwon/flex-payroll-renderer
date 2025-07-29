import React, { useState, useEffect } from 'react';
import logo from './logo.svg';
import './App.css';

const App = () => {
  const [data] = useState(window.initialData);

  return (
    <div className="App">
      <header className="App-header">
        <img src={logo} className="App-logo" alt="logo" />
        <p>
          Hello { data.name }
        </p>
      </header>
    </div>
  );
}

export default App;
