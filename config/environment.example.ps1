# Copy values into your terminal or an untracked private local file. No real secrets belong here.
$env:DB_URL='jdbc:mysql://localhost:3306/smarttrack'
$env:DB_USER='smarttrack'
$databasePassword=Read-Host 'Database password' -AsSecureString
$env:DB_PASSWORD=[System.Net.NetworkCredential]::new('', $databasePassword).Password
$env:SMARTTRACK_USER=Read-Host 'Backend username'
$userPassword=Read-Host 'Backend bootstrap password (12+ characters)' -AsSecureString
$env:SMARTTRACK_PASSWORD=[System.Net.NetworkCredential]::new('', $userPassword).Password
$env:MOCK_ENABLED='false'
$env:CORS_ORIGINS=''
