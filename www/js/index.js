/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
var app = {
    /* sumorobot code for interactive mode */
    sumorobot_code: "",
    /* application constructor */
    initialize: function() {
        /* add deviceready event listener */
        document.addEventListener("deviceready", app.onDeviceReady, false);
    },

    /* function that will be called when device APIs are available */
    onDeviceReady: function() {
        /* get the device language */
        navigator.globalization.getLocaleName(
            function (locale) {
                var language = locale.value.substring(0, 2);
                /* when language is not supported */
                if (language !== "en" && language !== "de" && language !== "et") {
                    /* choose english as default */
                    language = "en";
                }

                var buttons = {
                    "en": ["select your sumorobot"],
                    "de": ["waelhe dein sumoroboter"],
                    "et": ["vali oma sumorobot"]
                };
                document.getElementById('select').value = buttons[language][0];

                /* load the language */
                var script = document.createElement('script');
                script.type = 'text/javascript';
                script.src = 'js/blockly/msg/js/' + language + '.js';
                document.head.appendChild(script);

                /* wait for script to load */
                script.onload = function() {
                    /* initialize Blockly */
                    Blockly.inject(document.body, {
                        trashcan: true,                             // show the trashcan
                        path: "js/blockly/",                        // Blockly's path
                        toolbox: document.getElementById('toolbox') // the toolbox element
                    });
                    /* add change listener to Blockly */
                    Blockly.addChangeListener(app.onCodeChanged);
                    /* remove Blockly's border */
                    var elements = document.getElementsByClassName('blocklySvg')[0].style.border = "none";
                };
            },
            function (error) { app.showMessage("Error getting lsystem anguage: " + error); }
        );
    },

    command: function(cmd, arg) {
        /* assemble JSON message */
        if (typeof(arg) === 'undefined') arg = "";
        var msg = {cmd: cmd, arg: arg};
        msg.id = Math.random().toString(36).substr(2, 10);
        /* send it to the sumorobot */
        app.pluginCallback("Sumorobot", "sendCommands", [JSON.stringify(msg)+"\n"]);
    },

    functions: [],
    conditions: [],
    condition_index: 0,
    has_conditions: false,
    sumorobotResponse: function(data) {
        if (app.has_conditions == false) return;
        if (typeof(data) !== 'undefined') {
            /* the received data from the sumorobot */
            console.log("received response data: " + data);
        } else {
            app.condition_index = 0;
            /* Evaluate the first conditions */
            console.log("executing first condition: " + app.conditions[app.condition_index]);
            eval(app.conditions[app.condition_index]);
            return;
        }
        /* When the condition was true */
        if (data == true) {
            /* Execute the specified functions */
            console.log("condition true: " + app.conditions[app.condition_index]);
            eval(app.functions[app.condition_index]);
            setTimeout(function() { app.sumorobotResponse(); }, 100);
        /* When there are more conditions */
        } else if (app.condition_index < app.conditions.length - 1) {
            console.log("executing next condition: " + app.conditions[app.condition_index+1]);
            /* Evaluate the next condition */
            eval(app.conditions[++app.condition_index]);
        /* When we reached the else condition */
        } else if (app.condition_index == app.conditions.length - 1) {
            console.log("executing else functions: " + app.functions[app.condition_index+1]);
            eval(app.functions[++app.condition_index]);
            setTimeout(function() { app.sumorobotResponse(); }, 100);
        }
    },

    evaluateSumorobotCode: function() {
        var code = app.sumorobot_code;

        /* Replace functions with WebSocket calls */
        code = code.replace(/forward\(\)/g, "app.command('forward')");
        code = code.replace(/backward\(\)/g, "app.command('backward')");
        code = code.replace(/left\(\)/g, "app.command('left')");
        code = code.replace(/right\(\)/g, "app.command('right')");
        code = code.replace(/stop\(\)/g, "app.command('stop')");

        /* When there is a if clause */
        if (code.match(/if/)) {
            if (code.match(/false/)) return;
            /* Replace if clauses with WebSocket calls */
            code = code.replace(/\(ENEMY_LEFT\) \{/g, "app.command('enemy', 'left');");
            code = code.replace(/\(ENEMY_RIGHT\) \{/g, "app.command('enemy', 'right');");
            code = code.replace(/\(ENEMY_FRONT\) \{/g, "app.command('enemy', 'front');");
            code = code.replace(/\(LINE_LEFT\) \{/g, "app.command('line', 'left');");
            code = code.replace(/\(LINE_RIGHT\) \{/g, "app.command('line', 'right');");
            code = code.replace(/\(LINE_FRONT\) \{/g, "app.command('line', 'front');");
            /* Construct a if call loop */
            app.functions = [];
            app.conditions = [];
            var temp_functions = "";
            var program_lines = code.split('\n');
            for (var i = 0; i < program_lines.length; i++) {
                /* When if or else line */
                if (program_lines[i].match(/(if|else)/g)) {
                    /* Store the condition */
                    if (program_lines[i].match(/if/g)) app.conditions.push(program_lines[i].replace(/(if|\} else if)/g, ""));
                    /* Store the functions for the previous condition */
                    if (program_lines[i].match(/else/g)) {
                        app.functions.push(temp_functions);
                        temp_functions = "";
                    }
                /* When functions */
                } else {
                    /* Append the program */
                    temp_functions += program_lines[i].trim();
                }
            }
            /* Store the functions for the else condition */
            app.functions.push(temp_functions.substring(0, temp_functions.length - 1));
            console.log("conditions: " + app.conditions);
            console.log("functions: " + app.functions);
            /* when conditional execution is not active */
            if (app.has_conditions == false) {
                app.has_conditions = true;
                /* start the execution */
                app.sumorobotResponse();
            }
        } else {
            /* no conditions, just evalate commands */
            app.has_conditions = false;
            console.log("evaluating: " + code);
            eval(code);
        }
    },

    onCodeChanged: function() {
        var current_code = Blockly.Sumorobot.workspaceToCode();
        /* Check if code changed, while whitespace removed */
        if (current_code.replace(/ /g, "") !== app.sumorobot_code.replace(/ /g, "")) {
            /* Store the change */
            app.sumorobot_code = current_code;
            console.log("changed: " + app.sumorobot_code);
            /* evaluate the new sumorobot code */
            app.evaluateSumorobotCode();
        }
    },

    /* function to start sumorobot discovery */
    selectSumorobot: function() {
        app.pluginCallback("Sumorobot", "selectSumorobot", []);
    },

    /* function to make a asynchronous call to the plugins */
    pluginCallback: function(plugin, action, args, successCallback, failureCallback) {
        cordova.exec(
            /* callback success function */
            function(response) {
                /* do something with the response message */
            },
            /* callback error fucntion */
            function(error) {
                /* show the error */
                app.showMessage(error);
            },
            plugin, // name of the plugin
            action, // name of the action
            args    // json arguments
        );
    },

    /* function to show alert messages */
    showMessage: function(message) {
        /* message, dismissed callback, button name */
        navigator.notification.alert(message, null, "Message", "OK");
    }
};
